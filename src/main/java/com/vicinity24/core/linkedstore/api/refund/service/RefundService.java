package com.vicinity24.core.linkedstore.api.refund.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.model.TransferReversal;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.refund.dto.RefundResponse;
import com.vicinity24.core.linkedstore.api.refund.entity.Refund;
import com.vicinity24.core.linkedstore.api.refund.entity.RefundStatus;
import com.vicinity24.core.linkedstore.api.refund.repository.RefundRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class RefundService {

    private final TransactionRepository transactionRepository;
    private final RefundRepository refundRepository;
    private final TransactionEventBroadcaster eventBroadcaster;
    private final ObjectMapper objectMapper;
    private final String stripeSecretKey;

    public RefundService(
            TransactionRepository transactionRepository,
            RefundRepository refundRepository,
            TransactionEventBroadcaster eventBroadcaster,
            ObjectMapper objectMapper,
            @Value("${stripe.secret-key:}") String stripeSecretKey
    ) {
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.eventBroadcaster = eventBroadcaster;
        this.objectMapper = objectMapper;
        this.stripeSecretKey = stripeSecretKey;
    }

    public Refund initiateRefund(
            UUID transactionId,
            Integer overrideAmountCents,
            String reason,
            Authentication callerAuth
    ) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Transaction not found: " + transactionId
                ));

        if (tx.getStatus() != TransactionStatus.PAID && tx.getStatus() != TransactionStatus.PICKED_UP) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transaction not eligible for refund: status=" + tx.getStatus()
            );
        }

        Optional<Refund> lastRefund = refundRepository.findTopByTransactionIdOrderByCreatedAtDesc(transactionId);
        if (lastRefund.isPresent() && lastRefund.get().getStatus() != RefundStatus.FAILED) {
            return lastRefund.get();
        }

        int customerRefundTotal = (overrideAmountCents != null)
                ? overrideAmountCents
                : tx.getTotalRetailCents();

        if (customerRefundTotal <= 0 || customerRefundTotal > tx.getTotalRetailCents()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid refund amount: " + customerRefundTotal
                            + " cents. Must be between 1 and " + tx.getTotalRetailCents() + " cents."
            );
        }

        BigDecimal proportion = BigDecimal.valueOf(customerRefundTotal)
                .divide(BigDecimal.valueOf(tx.getTotalRetailCents()), 6, RoundingMode.DOWN);

        int reversedFulfillerInitial = proportion
                .multiply(BigDecimal.valueOf(tx.getWholesalePayoutCents()))
                .intValue();

        int reversedOriginatorInitial = proportion
                .multiply(BigDecimal.valueOf(tx.getArbitrageMarginCents()))
                .intValue();

        int totalReversedAttempt = reversedFulfillerInitial + reversedOriginatorInitial;
        int remainder = customerRefundTotal - totalReversedAttempt;
        if (remainder >= 1 && remainder <= 3) {
            reversedFulfillerInitial += remainder;
        }

        Refund refund = Refund.builder()
                .transactionId(transactionId)
                .status(RefundStatus.PENDING)
                .totalRefundedCents(customerRefundTotal)
                .reversedFulfillerCents(0)
                .reversedOriginatorCents(0)
                .currency("usd")
                .reason(reason)
                .createdByUserId(extractUserIdFromAuth(callerAuth))
                .createdByStoreId(extractStoreIdFromAuth(callerAuth))
                .build();
        refund = refundRepository.saveAndFlush(refund);

        com.stripe.model.Refund stripeRefund;
        try {
            applyStripeApiKey();

            String piId = tx.getStripePaymentIntentId();
            if (piId == null || piId.isBlank()) {
                refund.setStatus(RefundStatus.FAILED);
                refund.setStripeError("Transaction has no Stripe PaymentIntent ID; direct refunds not supported for this charge path");
                refundRepository.save(refund);
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        refund.getStripeError()
                );
            }

            PaymentIntent.retrieve(piId);

            stripeRefund = executeStripeRefund(refund, piId, customerRefundTotal, reason);
            refund.setStripeRefundId(stripeRefund.getId());

        } catch (StripeException e) {
            log.warn("RefundService: Stripe refund API call failed for txId={}", transactionId, e);
            refund.setStatus(RefundStatus.FAILED);
            refund.setStripeError(e.getUserMessage() != null ? e.getUserMessage() : e.getMessage());
            refundRepository.save(refund);
            broadcastRefundFailed(tx, refund);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Stripe refund API call failed: " + e.getMessage()
            );
        }

        int actualFulfillerReversed = 0;
        int actualOriginatorReversed = 0;
        StringBuilder reversalErrors = new StringBuilder();

        try {
            applyStripeApiKey();
            String transferGroup = "tx_" + transactionId.toString();

            TransferListParams listParams = TransferListParams.builder()
                    .setTransferGroup(transferGroup)
                    .build();
            TransferCollection transfers = Transfer.list(listParams);
            List<Transfer> txTransfers = transfers.getData();

            for (Transfer transfer : txTransfers) {
                String route = (transfer.getMetadata() != null)
                        ? transfer.getMetadata().getOrDefault("route", "")
                        : "";

                boolean isFulfillerRoute = "WHOLESALE_TO_FULFILLING".equals(route)
                        || (!route.isEmpty() && route.toUpperCase().contains("WHOLESALE"))
                        || (transfer.getAmount() != null && transfer.getAmount() == (long) tx.getWholesalePayoutCents());

                boolean isOriginRoute = "MARGIN_TO_ORIGINATING".equals(route)
                        || (!route.isEmpty() && route.toUpperCase().contains("MARGIN"))
                        || (transfer.getAmount() != null && transfer.getAmount() == (long) tx.getArbitrageMarginCents());

                long alreadyRev = 0L;
                if (transfer.getReversals() != null
                        && transfer.getReversals().getData() != null
                        && !transfer.getReversals().getData().isEmpty()) {
                    for (TransferReversal rev : transfer.getReversals().getData()) {
                        alreadyRev += rev.getAmount() != null ? rev.getAmount() : 0L;
                    }
                }
                long transferAmount = transfer.getAmount() != null ? transfer.getAmount() : 0L;
                long remainingOnTransfer = transferAmount - alreadyRev;

                long targetProRata = 0L;
                if (isFulfillerRoute) {
                    targetProRata = reversedFulfillerInitial;
                } else if (isOriginRoute) {
                    targetProRata = reversedOriginatorInitial;
                } else if (remainingOnTransfer > 0) {
                    targetProRata = remainingOnTransfer;
                }

                long targetAmount = Math.min(remainingOnTransfer, Math.max(0L, targetProRata));

                if (targetAmount > 0) {
                    try {
                        TransferReversalCollectionCreateParams revParams = TransferReversalCollectionCreateParams.builder()
                                .setAmount(targetAmount)
                                .build();
                        TransferReversal rev = transfer.getReversals().create(revParams);
                        long reversedAmt = rev.getAmount() != null ? rev.getAmount() : 0L;
                        if (isFulfillerRoute) {
                            actualFulfillerReversed += (int) reversedAmt;
                        } else if (isOriginRoute) {
                            actualOriginatorReversed += (int) reversedAmt;
                        } else {
                            actualFulfillerReversed += (int) reversedAmt;
                        }
                        log.info("RefundService: reversed transfer {} route={} amount={}c",
                                transfer.getId(), route, reversedAmt);
                    } catch (StripeException e) {
                        log.warn("RefundService: transfer reverse failed id={} route={}",
                                transfer.getId(), route, e);
                        reversalErrors.append("Transfer ")
                                .append(transfer.getId())
                                .append(" route ")
                                .append(route)
                                .append(" reverse failed: ")
                                .append(e.getMessage())
                                .append("; ");
                    }
                }
            }
        } catch (StripeException e) {
            log.warn("RefundService: Transfer.list failed for txId={}", transactionId, e);
            reversalErrors.append("Transfer group listing failed: ")
                    .append(e.getMessage())
                    .append("; ");
        }

        refund.setReversedFulfillerCents(actualFulfillerReversed);
        refund.setReversedOriginatorCents(actualOriginatorReversed);

        if (reversalErrors.length() > 0) {
            refund.setStatus(RefundStatus.FAILED);
            refund.setStripeError("Refund succeeded (customer reversed) but some transfer reversals failed: "
                    + reversalErrors);
            refundRepository.save(refund);
            broadcastRefundFailed(tx, refund);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    refund.getStripeError()
            );
        }

        refund.setStatus(RefundStatus.COMPLETED);
        refund.setCompletedAt(OffsetDateTime.now());
        refund = refundRepository.save(refund);

        broadcastRefundCompleted(tx, refund);

        return refund;
    }

    public List<RefundResponse> listForTransaction(UUID txId, String viewerPerspective) {
        return refundRepository.findAllByTransactionIdOrderByCreatedAtDesc(txId)
                .stream()
                .map(r -> RefundResponse.from(r, viewerPerspective))
                .toList();
    }

    public RefundResponse findById(UUID refundId, String viewerPerspective) {
        return refundRepository.findById(refundId)
                .map(r -> RefundResponse.from(r, viewerPerspective))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Refund not found"
                ));
    }

    public Set<UUID> findRefundedTxIdsForStore(UUID storeId) {
        Objects.requireNonNull(storeId, "storeId required");
        return refundRepository.findCompletedRefundedTxIdsForStore(storeId);
    }

    private void applyStripeApiKey() {
        if (stripeSecretKey != null && !stripeSecretKey.isBlank()) {
            Stripe.apiKey = stripeSecretKey;
        }
    }

    private com.stripe.model.Refund executeStripeRefund(
            Refund refund,
            String paymentIntentId,
            int customerRefundTotal,
            String reason
    ) throws StripeException {
        RefundCreateParams.Builder builder = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setAmount(Long.valueOf(customerRefundTotal))
                .setReverseTransfer(true)
                .setRefundApplicationFee(true)
                .putMetadata("refund_id", refund.getId().toString())
                .putMetadata("transaction_id", refund.getTransactionId().toString());

        if (reason != null && !reason.isBlank()) {
            builder.setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
            builder.putMetadata("reason_text", reason);
        }

        return com.stripe.model.Refund.create(builder.build());
    }

    private void broadcastRefundCompleted(Transaction tx, Refund refund) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            BigDecimal retailPrice = BigDecimal.valueOf(refund.getTotalRefundedCents())
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);

            String message = "Refunded "
                    + formatCents(refund.getTotalRefundedCents())
                    + ": host margin reversed "
                    + formatCents(refund.getReversedOriginatorCents())
                    + ", fulfiller wholesale reversed "
                    + formatCents(refund.getReversedFulfillerCents());

            TxEvent event = TxEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .type(TxEventType.REFUND_COMPLETED)
                    .createdAt(now)
                    .transactionId(tx.getId())
                    .storeId(tx.getOriginatingStoreId())
                    .fulfillingStoreId(tx.getFulfillingStoreId())
                    .originatingStoreId(tx.getOriginatingStoreId())
                    .variantId(tx.getVariantId())
                    .productId(tx.getProductId())
                    .productTitle(null)
                    .productImageUrl(null)
                    .sku(null)
                    .retailPrice(retailPrice)
                    .currency("USD")
                    .expiresAt(null)
                    .countdownSeconds(null)
                    .qrFallbackCode(null)
                    .runnerId(null)
                    .status("REFUND_COMPLETED")
                    .message(message)
                    .build();

            eventBroadcaster.broadcast(event);
            log.info("RefundService: broadcast REFUND_COMPLETED txId={} refundId={}",
                    tx.getId(), refund.getId());
        } catch (Exception ex) {
            log.warn("RefundService: broadcast REFUND_COMPLETED failed txId={}", tx.getId(), ex);
        }
    }

    private void broadcastRefundFailed(Transaction tx, Refund refund) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            BigDecimal retailPrice = BigDecimal.valueOf(refund.getTotalRefundedCents())
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);

            String message = refund.getStripeError() != null
                    ? refund.getStripeError()
                    : "Refund failed for transaction " + tx.getId();

            TxEvent event = TxEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .type(TxEventType.REFUND_FAILED)
                    .createdAt(now)
                    .transactionId(tx.getId())
                    .storeId(null)
                    .fulfillingStoreId(tx.getFulfillingStoreId())
                    .originatingStoreId(tx.getOriginatingStoreId())
                    .variantId(tx.getVariantId())
                    .productId(tx.getProductId())
                    .productTitle(null)
                    .productImageUrl(null)
                    .sku(null)
                    .retailPrice(retailPrice)
                    .currency("USD")
                    .expiresAt(null)
                    .countdownSeconds(null)
                    .qrFallbackCode(null)
                    .runnerId(null)
                    .status("REFUND_FAILED")
                    .message(message)
                    .build();

            eventBroadcaster.broadcast(event);
            log.info("RefundService: broadcast REFUND_FAILED txId={} refundId={}",
                    tx.getId(), refund.getId());
        } catch (Exception ex) {
            log.warn("RefundService: broadcast REFUND_FAILED failed txId={}", tx.getId(), ex);
        }
    }

    private static String formatCents(Integer cents) {
        if (cents == null) cents = 0;
        BigDecimal dollars = BigDecimal.valueOf(cents)
                .setScale(2, RoundingMode.UNNECESSARY)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
        return "$" + dollars.toPlainString();
    }

    private String extractUserIdFromAuth(Authentication a) {
        if (a == null) return null;
        if (a.getName() != null && !a.getName().isBlank()) {
            return a.getName();
        }
        if (a.getPrincipal() != null) {
            return a.getPrincipal().toString();
        }
        return null;
    }

    private String extractStoreIdFromAuth(Authentication a) {
        return null;
    }
}
