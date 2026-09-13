package com.vicinity24.core.linkedstore.api.service;

import com.stripe.Stripe;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.CheckoutPayRequest;
import com.vicinity24.core.linkedstore.api.dto.CheckoutPayResponse;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.exception.*;
import com.vicinity24.core.linkedstore.api.payment.PayoutRequest;
import com.vicinity24.core.linkedstore.api.payment.PaymentProvider;
import com.vicinity24.core.linkedstore.api.payment.PaymentProviderFactory;
import com.vicinity24.core.linkedstore.api.payment.PaymentRequest;
import com.vicinity24.core.linkedstore.api.payment.PaymentResponse;
import com.vicinity24.core.linkedstore.api.payment.PayoutResponse;
import com.vicinity24.core.linkedstore.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final StoreRepository storeRepository;
    private final StoreUserRepository storeUserRepository;
    private final QrTokenRepository qrTokenRepository;
    private final StripeConfig stripeConfig;
    private final PaymentProviderFactory paymentProviderFactory;

    private static final int QR_TOKEN_TTL_MINUTES = 60;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public CheckoutPayResponse processPayment(CheckoutPayRequest request) {
        UUID transactionId = request.getTransactionId();

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId.toString()));

        if (transaction.getStatus() == TransactionStatus.EXPIRED
                || transaction.getStatus() == TransactionStatus.CANCELED) {
            throw new ReservationExpiredException(
                    "Transaction cannot be paid: current status is " + transaction.getStatus(),
                    transactionId, null, null);
        }

        if (transaction.getStatus() == TransactionStatus.PAID
                || transaction.getStatus() == TransactionStatus.PICKED_UP) {
            throw new TransactionStateException(
                    "Transaction is already paid or picked up",
                    transactionId, transaction.getStatus(), TransactionStatus.RESERVED);
        }

        List<InventoryLock> locks = inventoryLockRepository.findByTransactionId(transactionId);
        if (locks.isEmpty()) {
            throw new ResourceNotFoundException("InventoryLock for Transaction", transactionId.toString());
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (InventoryLock lock : locks) {
            if (lock.getStatus() != InventoryLockStatus.HELD) {
                throw new ReservationExpiredException(
                        "Inventory lock is no longer held: " + lock.getStatus(),
                        transactionId, lock.getId(), lock.getExpiresAt());
            }
            if (lock.getExpiresAt().isBefore(now)) {
                throw new ReservationExpiredException(
                        "15-minute reservation window has expired",
                        transactionId, lock.getId(), lock.getExpiresAt());
            }
        }

        UUID origStoreId = transaction.getOriginatingStoreId();
        UUID fulfillsStoreId = transaction.getFulfillingStoreId();
        Store originatingStore = storeRepository.findById(origStoreId)
                .orElseThrow(() -> new ResourceNotFoundException("Originating Store", origStoreId.toString()));
        Store fulfillingStore = storeRepository.findById(fulfillsStoreId)
                .orElseThrow(() -> new ResourceNotFoundException("Fulfilling Store", fulfillsStoreId.toString()));

        String transferGroup = "tx_" + transactionId.toString().replace("-", "");

        int totalCents = transaction.getTotalRetailCents();
        int wholesalePayoutCents = transaction.getWholesalePayoutCents();
        int marginCents = transaction.getArbitrageMarginCents();
        int platformFeeCents = 0;

        PaymentProvider paymentProvider = paymentProviderFactory.getProvider(request.getPaymentProvider());
        PaymentResponse paymentIntent;
        try {
            paymentIntent = capturePaymentWithProvider(
                    paymentProvider, request, totalCents, transferGroup,
                    originatingStore, fulfillingStore);
        } catch (StripePaymentFailedException e) {
            log.error("Provider {} payment capture failed for tx={}",
                    paymentProvider.providerId(), transactionId, e);
            throw e;
        }

        transaction.setStripePaymentIntentId(paymentIntent.getPaymentIntentId());
        transaction.setStatus(TransactionStatus.PAID);
        transaction = transactionRepository.save(transaction);

        locks.forEach(lock -> inventoryLockRepository.updateStatusIfCurrentStatusIs(
                lock.getId(), InventoryLockStatus.RELEASED_TO_SALE, InventoryLockStatus.HELD));

        try {
            executeSplitLedgerPayouts(
                    paymentProvider,
                    transferGroup,
                    wholesalePayoutCents,
                    marginCents,
                    originatingStore.getStripeConnectId(),
                    fulfillingStore.getStripeConnectId());
        } catch (StripePaymentFailedException e) {
            log.error("Provider {} split payouts failed for tx={}, payment_intent={}",
                    paymentProvider.providerId(), transactionId, paymentIntent.getPaymentIntentId(), e);
        }

        UUID runnerId = assignRunnerForOriginatingStore(originatingStore.getId());

        String secureToken = generateSecureToken(transactionId, runnerId, now);
        String fallbackCode = generateFallbackCode();
        OffsetDateTime qrExpiresAt = now.plusMinutes(QR_TOKEN_TTL_MINUTES);

        QrToken qrToken = QrToken.builder()
                .transactionId(transactionId)
                .runnerId(runnerId)
                .secureToken(secureToken)
                .fallbackCode(fallbackCode)
                .expiresAt(qrExpiresAt)
                .build();
        qrToken = qrTokenRepository.save(qrToken);

        log.info("Checkout complete: tx={}, pi={}, group={}, wholesale={}c, margin={}c, platform={}c, qrTokenId={}",
                transactionId, paymentIntent.getPaymentIntentId(), transferGroup,
                wholesalePayoutCents, marginCents, platformFeeCents, qrToken.getId());

        return CheckoutPayResponse.builder()
                .transactionId(transaction.getId())
                .transactionStatus(transaction.getStatus().name())
                .stripePaymentIntentId(paymentIntent.getPaymentIntentId())
                .transferGroup(transferGroup)
                .totalRetailCents(totalCents)
                .wholesalePayoutCents(wholesalePayoutCents)
                .arbitrageMarginCents(marginCents)
                .platformFeeCents(platformFeeCents)
                .qrSecureToken(secureToken)
                .qrFallbackCode(fallbackCode)
                .qrTokenId(qrToken.getId())
                .qrExpiresAt(qrExpiresAt.toString())
                .runnerId(runnerId)
                .build();
    }

    private PaymentResponse capturePaymentWithProvider(
            PaymentProvider provider,
            CheckoutPayRequest request,
            int totalCents,
            String transferGroup,
            Store originatingStore,
            Store fulfillingStore) {

        String description = "Linked-Store Purchase | tx_" + request.getTransactionId();
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .provider(provider.providerId())
                .paymentMethodId(request.getPaymentMethodId())
                .customerEmail(request.getCustomerEmail())
                .idempotencyKey(request.getIdempotencyKey())
                .amountCents(totalCents)
                .currency("usd")
                .description(description)
                .transferGroup(transferGroup)
                .confirm(true)
                .metadata(Map.of(
                        "originating_store_id", originatingStore.getId().toString(),
                        "fulfilling_store_id", fulfillingStore.getId().toString(),
                        "transaction_id", request.getTransactionId().toString()
                ))
                .build();
        return provider.capturePayment(paymentRequest);
    }

    private void executeSplitLedgerPayouts(
            PaymentProvider provider,
            String transferGroup,
            int wholesalePayoutCents,
            int marginCents,
            String originatingStripeConnectId,
            String fulfillingStripeConnectId) {

        if (wholesalePayoutCents > 0) {
            PayoutRequest pr = PayoutRequest.builder()
                    .provider(provider.providerId())
                    .amountCents(wholesalePayoutCents)
                    .currency("usd")
                    .destinationAccountId(fulfillingStripeConnectId)
                    .transferGroup(transferGroup)
                    .route("WHOLESALE_TO_FULFILLING")
                    .build();
            PayoutResponse tx = provider.payout(pr);
            log.info("{} wholesale payout: id={}, amount={}c, dest={}, group={}",
                    provider.providerId(), tx.getPayoutId(), wholesalePayoutCents, fulfillingStripeConnectId, transferGroup);
        }

        if (marginCents > 0) {
            PayoutRequest pr = PayoutRequest.builder()
                    .provider(provider.providerId())
                    .amountCents(marginCents)
                    .currency("usd")
                    .destinationAccountId(originatingStripeConnectId)
                    .transferGroup(transferGroup)
                    .route("MARGIN_TO_ORIGINATING")
                    .build();
            PayoutResponse tx = provider.payout(pr);
            log.info("{} margin payout: id={}, amount={}c, dest={}, group={}",
                    provider.providerId(), tx.getPayoutId(), marginCents, originatingStripeConnectId, transferGroup);
        }

        log.debug("Split ledger complete for provider={} group={}: wholesale={}c -> fulfilling, margin={}c -> originating, platform=0c",
                provider.providerId(), transferGroup, wholesalePayoutCents, marginCents);
    }

    private UUID assignRunnerForOriginatingStore(UUID originatingStoreId) {
        List<StoreUser> runners = storeUserRepository.findByStoreIdAndRole(
                originatingStoreId, StoreUserRole.RUNNER);
        if (!runners.isEmpty()) {
            return runners.get(0).getId();
        }

        List<StoreUser> owners = storeUserRepository.findByStoreIdAndRole(
                originatingStoreId, StoreUserRole.OWNER);
        if (!owners.isEmpty()) {
            return owners.get(0).getId();
        }

        List<StoreUser> clerks = storeUserRepository.findByStoreIdAndRole(
                originatingStoreId, StoreUserRole.CLERK);
        if (!clerks.isEmpty()) {
            return clerks.get(0).getId();
        }

        List<StoreUser> fallback = storeUserRepository.findByStoreId(originatingStoreId);
        if (fallback.isEmpty()) {
            throw new ResourceNotFoundException("StoreUser for Store", originatingStoreId.toString());
        }
        return fallback.get(0).getId();
    }

    private String generateSecureToken(UUID transactionId, UUID runnerId, OffsetDateTime timestamp) {
        try {
            byte[] randomBytes = new byte[32];
            SECURE_RANDOM.nextBytes(randomBytes);
            String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = transactionId.toString() + "|" + runnerId.toString()
                    + "|" + timestamp.toString() + "|" + randomPart + "|" + Stripe.apiKey.hashCode();
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            String hashPart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hash).substring(0, 16);

            return "ls_" + randomPart + hashPart;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String generateFallbackCode() {
        for (int i = 0; i < 10; i++) {
            int n = SECURE_RANDOM.nextInt(100_000_000);
            String code = String.format("%08d", n);
            if (!qrTokenRepository.existsByFallbackCode(code)) {
                return code;
            }
        }
        long nano = System.nanoTime() % 100_000_000L;
        return String.format("%08d", nano);
    }

    public Transaction getPaidTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .filter(tx -> tx.getStatus() == TransactionStatus.PAID
                        || tx.getStatus() == TransactionStatus.PICKED_UP)
                .orElseThrow(() -> new TransactionStateException(
                        "Transaction is not in paid or picked_up state",
                        transactionId, null, TransactionStatus.PAID));
    }
}
