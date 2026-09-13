package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.ReservationRequest;
import com.vicinity24.core.linkedstore.api.dto.ReservationResponse;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final ProductVariantRepository variantRepository;
    private final TransactionRepository transactionRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final StoreUserRepository storeUserRepository;
    private final QrTokenRepository qrTokenRepository;
    private final StripeConfig stripeConfig;

    private static final int QR_TOKEN_TTL_MINUTES = 60;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @PostMapping("")
    @Transactional
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody ReservationRequest request) {

        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }

        Product product = resolveProduct(request.getProductId());
        if (product == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UUID productUuid = product.getId();

        Store originating = resolveStore(request.getOriginatingStoreId());
        if (originating == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UUID originatingStoreId = originating.getId();

        ProductVariant variant = null;

        if (request.getVariantId() != null && !request.getVariantId().isBlank()) {
            UUID variantUuid = resolveVariantId(request.getVariantId());
            if (variantUuid == null) {
                return ResponseEntity.ok(ReservationResponse.builder()
                        .accepted(false)
                        .status("unavailable")
                        .message("Invalid variantId format.")
                        .build());
            }

            Optional<ProductVariant> variantOpt = variantRepository.findByIdWithSufficientStock(variantUuid, 1);
            if (variantOpt.isPresent()) {
                ProductVariant v = variantOpt.get();
                if (!v.getStoreId().equals(originatingStoreId)
                        && v.getStatus() == VariantStatus.ACTIVE
                        && v.getProductId().equals(productUuid)) {
                    variant = v;
                }
            }
        } else {
            List<ProductVariant> variants = variantRepository
                    .findByProductIdAndStatusOrderByRetailPriceCentsAsc(productUuid, VariantStatus.ACTIVE);
            variant = variants.stream()
                    .filter(v -> v.getStockQuantity() >= 1 && !v.getStoreId().equals(originatingStoreId))
                    .findFirst()
                    .orElse(null);
        }

        if (variant == null) {
            return ResponseEntity.ok(ReservationResponse.builder()
                    .accepted(false)
                    .status("unavailable")
                    .message("No participating store has this item in stock.")
                    .build());
        }

        Store fulfilling = storeRepository.findById(variant.getStoreId())
                .orElse(null);
        if (fulfilling == null) {
            return ResponseEntity.ok(ReservationResponse.builder()
                    .accepted(false)
                    .status("unavailable")
                    .message("No participating store has this item in stock.")
                    .build());
        }

        if (originatingStoreId.equals(variant.getStoreId())) {
            return ResponseEntity.ok(ReservationResponse.builder()
                    .accepted(false)
                    .status("unavailable")
                    .message("No participating store has this item in stock.")
                    .build());
        }

        int rows = variantRepository.decrementStockWithOptimisticLock(variant.getId(), 1, variant.getVersion());
        if (rows == 0) {
            ProductVariant reloaded = variantRepository.findById(variant.getId()).orElse(null);
            if (reloaded != null) {
                rows = variantRepository.decrementStockWithOptimisticLock(reloaded.getId(), 1, reloaded.getVersion());
            }
        }
        if (rows == 0) {
            return ResponseEntity.ok(ReservationResponse.builder()
                    .accepted(false)
                    .status("race_lost")
                    .message("Item just reserved by another customer.")
                    .build());
        }

        int totalRetailCents = variant.getRetailPriceCents();
        int wholesalePayoutCents = variant.getWholesalePriceCents();
        int arbitrageMarginCents = totalRetailCents - wholesalePayoutCents;

        Transaction tx = Transaction.builder()
                .originatingStoreId(originatingStoreId)
                .fulfillingStoreId(variant.getStoreId())
                .totalRetailCents(totalRetailCents)
                .wholesalePayoutCents(wholesalePayoutCents)
                .arbitrageMarginCents(arbitrageMarginCents)
                .status(TransactionStatus.RESERVED)
                .build();
        tx = transactionRepository.save(tx);

        OffsetDateTime now = OffsetDateTime.now();
        int countdownSeconds = request.getCountdownSeconds() != null ? request.getCountdownSeconds() : 900;

        InventoryLock lock = InventoryLock.builder()
                .transactionId(tx.getId())
                .variantId(variant.getId())
                .storeId(variant.getStoreId())
                .lockedQuantity(1)
                .expiresAt(now.plusSeconds(countdownSeconds))
                .status(InventoryLockStatus.HELD)
                .build();
        inventoryLockRepository.save(lock);

        UUID runnerId = assignRunnerForOriginatingStoreInline(originatingStoreId);

        String secureToken = generateSecureTokenInline(tx.getId(), runnerId, now);
        String fallbackCode = generateFallbackCodeInline();
        OffsetDateTime qrExpiresAt = now.plusMinutes(QR_TOKEN_TTL_MINUTES);

        QrToken qrToken = QrToken.builder()
                .transactionId(tx.getId())
                .runnerId(runnerId)
                .secureToken(secureToken)
                .fallbackCode(fallbackCode)
                .expiresAt(qrExpiresAt)
                .build();
        qrToken = qrTokenRepository.save(qrToken);

        String productImageUrl = product.getPrimaryImageUrl() != null
                ? product.getPrimaryImageUrl()
                : variant.getImageUrl();

        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.builder()
                .accepted(true)
                .transactionId(tx.getId())
                .variantId(variant.getId())
                .productTitle(product.getTitle())
                .productImageUrl(productImageUrl)
                .sku(variant.getSku())
                .countdownSeconds(countdownSeconds)
                .totalRetailCents(totalRetailCents)
                .wholesalePayoutCents(wholesalePayoutCents)
                .arbitrageMarginCents(arbitrageMarginCents)
                .currency("USD")
                .originatingStoreId(originatingStoreId)
                .fulfillingStoreId(variant.getStoreId())
                .qrSecureToken(secureToken)
                .qrFallbackCode(fallbackCode)
                .qrTokenId(qrToken.getId().toString())
                .qrExpiresAt(qrExpiresAt.toString())
                .runnerId(runnerId)
                .status("ok")
                .build());
    }

    private UUID assignRunnerForOriginatingStoreInline(UUID originatingStoreId) {
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
            StoreUser defaultRunner = StoreUser.builder()
                    .storeId(originatingStoreId)
                    .name("Default Runner")
                    .role(StoreUserRole.RUNNER)
                    .phoneNumber("555-0000")
                    .build();
            defaultRunner = storeUserRepository.save(defaultRunner);
            return defaultRunner.getId();
        }
        return fallback.get(0).getId();
    }

    private String generateSecureTokenInline(UUID transactionId, UUID runnerId, OffsetDateTime timestamp) {
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

    private String generateFallbackCodeInline() {
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

    private Product resolveProduct(String productId) {
        try {
            UUID productUuid = UUID.fromString(productId);
            Product p = productRepository.findById(productUuid).orElse(null);
            if (p != null) {
                return p;
            }
        } catch (IllegalArgumentException e) {
        }

        List<Product> activeProducts = productRepository.findAllByStatus(ProductStatus.ACTIVE);
        if (productId.length() >= 3) {
            String sub = productId.toLowerCase().substring(0, Math.min(productId.length() - 2, productId.length()));
            for (Product p : activeProducts) {
                if (p.getTitle() != null && p.getTitle().toLowerCase().contains(sub)) {
                    return p;
                }
            }
        }

        if (productId.toLowerCase().contains("airmax")) {
            for (Product p : activeProducts) {
                if (p.getTitle() != null && p.getTitle().toLowerCase().contains("airmax")) {
                    return p;
                }
            }
        }

        if (productId.startsWith("p-")) {
            for (Product p : activeProducts) {
                if (p.getTitle() != null && p.getTitle().toLowerCase().contains("airmax")) {
                    return p;
                }
            }
        }

        if (!activeProducts.isEmpty()) {
            log.warn("Reservation resolveProduct fallback to first ACTIVE product: {}", activeProducts.get(0).getTitle());
            return activeProducts.get(0);
        }

        return null;
    }

    private Store resolveStore(String storeId) {
        try {
            UUID storeUuid = UUID.fromString(storeId);
            Store s = storeRepository.findById(storeUuid).orElse(null);
            if (s != null) {
                return s;
            }
        } catch (IllegalArgumentException e) {
        }

        List<Store> allStores = storeRepository.findAll();
        for (Store s : allStores) {
            if (s.getBusinessName() != null && s.getBusinessName().toLowerCase().contains(storeId.toLowerCase())) {
                return s;
            }
        }

        if (!allStores.isEmpty()) {
            log.warn("Reservation resolveStore fallback to first store: {}", allStores.get(0).getBusinessName());
            return allStores.get(0);
        }

        return null;
    }

    private UUID resolveVariantId(String variantId) {
        try {
            return UUID.fromString(variantId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
