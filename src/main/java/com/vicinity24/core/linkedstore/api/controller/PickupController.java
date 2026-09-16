package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Transfer;
import com.stripe.param.TransferCreateParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.PickupVerifyRequest;
import com.vicinity24.core.linkedstore.api.dto.PickupVerifyResponse;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
import com.vicinity24.core.linkedstore.api.entity.InventoryLock;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.QrToken;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.InventoryLockRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.QrTokenRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/pickup")
@RequiredArgsConstructor
public class PickupController {

    private final StripeConfig stripeConfig;
    private final QrTokenRepository qrTokenRepository;
    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final ProductVariantRepository productVariantRepository;
    private final TransactionEventBroadcaster eventBroadcaster;

    @PostMapping("/verify")
    @Transactional
    public ResponseEntity<PickupVerifyResponse> verify(
            @RequestBody PickupVerifyRequest request) {

        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }

        final String secureToken = (request.getSecureToken() != null) ? request.getSecureToken().trim() : null;
        final String fallbackCode = (request.getFallbackCode() != null) ? request.getFallbackCode().trim() : null;

        if ((secureToken == null || secureToken.isBlank())
                && (fallbackCode == null || fallbackCode.isBlank())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(PickupVerifyResponse.builder()
                    .status("invalid")
                    .message("Either secureToken (ls_ QR payload) or fallbackCode (8-digit) is required.")
                    .build());
        }

        final OffsetDateTime now = OffsetDateTime.now();

        Optional<QrToken> qrOpt = Optional.empty();
        if (secureToken != null && !secureToken.isBlank()) {
            qrOpt = qrTokenRepository.findValidTokenBySecureToken(secureToken, now);
        }
        if (qrOpt.isEmpty() && fallbackCode != null && !fallbackCode.isBlank()) {
            qrOpt = qrTokenRepository.findValidTokenByFallbackCode(
                    fallbackCode.replaceAll("\\s+", ""), now);
        }

        if (qrOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(PickupVerifyResponse.builder()
                    .status("invalid")
                    .message("Pickup QR / fallback code is expired, already scanned, or invalid.")
                    .build());
        }

        final QrToken qr = qrOpt.get();
        final int rows = qrTokenRepository.markAsScannedIfNotAlreadyScanned(qr.getId(), now);
        if (rows == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(PickupVerifyResponse.builder()
                    .status("already_scanned")
                    .message("This pickup QR was already used.")
                    .build());
        }

        final Optional<Transaction> txOpt = transactionRepository.findById(qr.getTransactionId());
        if (txOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(PickupVerifyResponse.builder()
                    .status("not_found")
                    .message("Transaction not found for this QR token.")
                    .build());
        }

        final Transaction tx = txOpt.get();
        if (tx.getStatus() != TransactionStatus.PAID && tx.getStatus() != TransactionStatus.PICKED_UP) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(PickupVerifyResponse.builder()
                    .status("wrong_state")
                    .message("Transaction must be paid before verifying pickup. Current status: " + tx.getStatus().name())
                    .build());
        }

        boolean justPickedUp = false;
        if (tx.getStatus() != TransactionStatus.PICKED_UP) {
            tx.setStatus(TransactionStatus.PICKED_UP);
            transactionRepository.save(tx);
            justPickedUp = true;
        }
        if (justPickedUp) {
            try {
                UUID variantId = inventoryLockRepository.findByTransactionId(tx.getId()).stream()
                        .findFirst().map(InventoryLock::getVariantId).orElse(null);
                String productTitle = null;
                String productImageUrl = null;
                String sku = null;
                BigDecimal retailPrice = null;
                OffsetDateTime expiresAt = inventoryLockRepository.findByTransactionId(tx.getId()).stream()
                        .findFirst().map(InventoryLock::getExpiresAt).orElse(null);
                if (variantId != null) {
                    Optional<ProductVariant> vOpt = productVariantRepository.findByIdWithProduct(variantId);
                    if (vOpt.isPresent()) {
                        ProductVariant v = vOpt.get();
                        sku = v.getSku();
                        if (v.getRetailPriceCents() != null) retailPrice = BigDecimal.valueOf(v.getRetailPriceCents()).scaleByPowerOfTen(-2);
                        if (v.getProduct() != null) {
                            productTitle = v.getProduct().getTitle();
                            productImageUrl = v.getProduct().getPrimaryImageUrl();
                        }
                        if (productImageUrl == null) productImageUrl = v.getImageUrl();
                    }
                }
                eventBroadcaster.broadcast(TxEvent.builder()
                        .type(TxEventType.PICKED_UP)
                        .createdAt(now)
                        .transactionId(tx.getId())
                        .storeId(tx.getFulfillingStoreId())
                        .fulfillingStoreId(tx.getFulfillingStoreId())
                        .originatingStoreId(tx.getOriginatingStoreId())
                        .variantId(variantId)
                        .productTitle(productTitle)
                        .productImageUrl(productImageUrl)
                        .sku(sku)
                        .retailPrice(retailPrice)
                        .currency("USD")
                        .expiresAt(expiresAt)
                        .status(tx.getStatus().name())
                        .message("Customer picked up order from store.")
                        .build());
            } catch (Exception ex) {
                log.warn("Pickup verify: broadcast PICKED_UP failed txId={}", tx.getId(), ex);
            }
        }

        String transferId = null;
        final Optional<Store> originatingOpt = storeRepository.findById(tx.getOriginatingStoreId());
        final Store originating = originatingOpt.orElse(null);
        final Integer marginCents = tx.getArbitrageMarginCents();

        if (originating != null
                && originating.getStripeConnectId() != null
                && !originating.getStripeConnectId().isBlank()
                && !originating.getStripeConnectId().startsWith("acct_connected_")
                && marginCents != null
                && marginCents > 0) {

            try {
                final TransferCreateParams params = TransferCreateParams.builder()
                        .setAmount(Long.valueOf(marginCents))
                        .setCurrency("usd")
                        .setDestination(originating.getStripeConnectId())
                        .setTransferGroup("tx_" + tx.getId())
                        .build();
                final Transfer transfer = Transfer.create(params);
                transferId = transfer.getId();
            } catch (StripeException e) {
                log.error("Failed to create Stripe Transfer for tx={} to store={}",
                        tx.getId(), originating.getId(), e);
                transferId = null;
            }
        }

        final String qrScannedAt = now.toString();
        final String status = transferId != null ? "settled" : "picked_up_not_settled";
        final String message = transferId != null
                ? "Custody verified. Margin successfully transferred to originating store."
                : "Custody verified. Transfer creation failed - please settle margin manually via Stripe dashboard.";

        final PickupVerifyResponse.PickupVerifyResponseBuilder responseBuilder = PickupVerifyResponse.builder()
                .status(status)
                .transactionId(tx.getId())
                .transactionStatus(tx.getStatus().name())
                .arbitrageMarginCents(marginCents)
                .currency("USD")
                .stripeTransferId(transferId)
                .qrScannedAt(qrScannedAt)
                .message(message);

        if (originating != null) {
            responseBuilder
                    .marginToStoreId(originating.getId())
                    .marginToStoreConnectId(originating.getStripeConnectId());
        }

        return ResponseEntity.ok(responseBuilder.build());
    }
}
