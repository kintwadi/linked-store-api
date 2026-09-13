package com.vicinity24.core.linkedstore.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicinity24.core.linkedstore.api.dto.TransactionResponse;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final ProductVariantRepository variantRepository;
    private final QrTokenRepository qrTokenRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable("id") UUID txId) {

        Transaction tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildTransactionResponse(tx));
    }

    @PostMapping("/{id}/mark-paid")
    @Transactional
    public ResponseEntity<TransactionResponse> markPaidTestOnly(
            @PathVariable("id") String idStr,
            @RequestParam(value = "stripePaymentIntentId", required = false) String pi) {
        UUID id;
        try { id = UUID.fromString(idStr); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
        Transaction tx = transactionRepository.findById(id).orElse(null);
        if (tx == null) return ResponseEntity.notFound().build();
        if (tx.getStatus() == TransactionStatus.PAID || tx.getStatus() == TransactionStatus.PICKED_UP) {
        } else {
            tx.setStatus(TransactionStatus.PAID);
            if (pi != null && !pi.isBlank()) tx.setStripePaymentIntentId(pi);
            tx = transactionRepository.save(tx);
        }
        return ResponseEntity.ok(buildTransactionResponse(tx));
    }

    private TransactionResponse buildTransactionResponse(Transaction tx) {
        UUID txId = tx.getId();

        String originatingStoreName = storeRepository.findById(tx.getOriginatingStoreId())
                .map(Store::getBusinessName)
                .orElse(null);

        String fulfillingStoreName = storeRepository.findById(tx.getFulfillingStoreId())
                .map(Store::getBusinessName)
                .orElse(null);

        UUID variantId = inventoryLockRepository.findByTransactionId(txId).stream()
                .findFirst()
                .map(InventoryLock::getVariantId)
                .orElse(null);

        if (variantId == null) {
            variantId = transactionItemRepository.findByTransactionId(txId).stream()
                    .findFirst()
                    .map(TransactionItem::getVariantId)
                    .orElse(null);
        }

        UUID productId = null;
        String productTitle = null;
        String productImageUrl = null;
        String sku = null;
        String variantAttributesJson = null;

        if (variantId != null) {
            Optional<ProductVariant> variantOpt = variantRepository.findByIdWithProduct(variantId);
            if (variantOpt.isPresent()) {
                ProductVariant variant = variantOpt.get();
                productId = variant.getProductId();
                sku = variant.getSku();

                if (variant.getProduct() != null) {
                    productTitle = variant.getProduct().getTitle();
                    productImageUrl = variant.getProduct().getPrimaryImageUrl() != null
                            ? variant.getProduct().getPrimaryImageUrl()
                            : variant.getImageUrl();
                } else {
                    productImageUrl = variant.getImageUrl();
                }

                if (variant.getVariantAttributes() != null) {
                    try {
                        variantAttributesJson = objectMapper.writeValueAsString(variant.getVariantAttributes());
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize variantAttributes for variantId={}", variantId, e);
                        variantAttributesJson = null;
                    }
                }
            }
        }

        QrToken qrToken = qrTokenRepository.findByTransactionId(txId).orElse(null);
        String qrSecureToken = qrToken != null ? qrToken.getSecureToken() : null;
        String qrFallbackCode = qrToken != null ? qrToken.getFallbackCode() : null;

        return TransactionResponse.builder()
                .id(tx.getId())
                .status(tx.getStatus().name())
                .originatingStoreId(tx.getOriginatingStoreId())
                .originatingStoreName(originatingStoreName)
                .fulfillingStoreId(tx.getFulfillingStoreId())
                .fulfillingStoreName(fulfillingStoreName)
                .stripePaymentIntentId(tx.getStripePaymentIntentId())
                .totalRetailCents(tx.getTotalRetailCents())
                .wholesalePayoutCents(tx.getWholesalePayoutCents())
                .arbitrageMarginCents(tx.getArbitrageMarginCents())
                .currency("USD")
                .productId(productId)
                .productTitle(productTitle)
                .productImageUrl(productImageUrl)
                .variantId(variantId)
                .sku(sku)
                .variantAttributesJson(variantAttributesJson)
                .qrSecureToken(qrSecureToken)
                .qrFallbackCode(qrFallbackCode)
                .createdAt(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null)
                .updatedAt(tx.getUpdatedAt() != null ? tx.getUpdatedAt().toString() : null)
                .build();
    }
}
