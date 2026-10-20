package com.vicinity24.core.linkedstore.api.service;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.CheckoutPayRequest;
import com.vicinity24.core.linkedstore.api.dto.CheckoutPayResponse;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.exception.*;
import com.vicinity24.core.linkedstore.api.payment.PayoutRequest;
import com.vicinity24.core.linkedstore.api.payment.PaymentProvider;
import com.vicinity24.core.linkedstore.api.payment.PaymentProviderFactory;
import com.vicinity24.core.linkedstore.api.payment.PaymentRequest;
import com.vicinity24.core.linkedstore.api.payment.PaymentResponse;
import com.vicinity24.core.linkedstore.api.payment.PayoutResponse;
import com.vicinity24.core.linkedstore.api.repository.*;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final StripeConfig stripeConfig;
    private final PaymentProviderFactory paymentProviderFactory;
    private final TransactionEventBroadcaster eventBroadcaster;
    private final PlatformContractService platformContractService;

    private static final int QR_TOKEN_TTL_MINUTES = 60;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String resolveStoreCurrencyLower(Store store) {
        if (store == null) return "usd";
        String c = store.getCurrencyCode();
        if (c == null || c.isBlank()) return "usd";
        return c.trim().toLowerCase(Locale.ROOT);
    }

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

        // Canonical transfer_group shared by the charge and the pickup-time transfers. Must
        // match settleOnPickup() and RefundService (which lists transfers by this exact group).
        String transferGroup = "tx_" + transactionId.toString();

        int totalCents = transaction.getTotalRetailCents();
        int wholesalePayoutCents = transaction.getWholesalePayoutCents();
        int marginCents = transaction.getArbitrageMarginCents();
        // The platform fee is DERIVED from the arbitrage margin (FEE_BASED mode) and is retained as a
        // residual when the split executes at pickup. It is never added on top of the customer's
        // charge — the customer is charged exactly the transaction's total retail amount.
        int platformFeeCents = platformContractService.isFeeBased()
                ? platformContractService.computePlatformFeeCents(marginCents)
                : 0;
        final String txCurrencyLower = resolveStoreCurrencyLower(fulfillingStore);
        final String txCurrencyUpper = txCurrencyLower.toUpperCase(Locale.ROOT);

        PaymentProvider paymentProvider = paymentProviderFactory.getProvider(request.getPaymentProvider());
        PaymentResponse paymentIntent;
        try {
            paymentIntent = capturePaymentWithProvider(
                    paymentProvider, request, totalCents, transferGroup,
                    originatingStore, fulfillingStore, txCurrencyLower);
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

        // Payment Flow 2 — Hold on Platform & Split at Product Pickup.
        // The full retail amount stays on the platform account after capture. No transfers are
        // created here; wholesale + margin transfers are executed by settleOnPickup() when the
        // customer picks up the product.

        // Runner: if the reservation flow already assigned one, reuse it.
        // Otherwise prefer fulfilling store runners (pickup happens there), else originating
        // store. If exactly one runner exists in the fulfilling store and runner was null,
        // auto-assign them to unblock the runner queue.
        UUID runnerId = transaction.getRunnerId();
        if (runnerId == null) {
            runnerId = assignRunnerForPickup(originatingStore.getId(), fulfillingStore.getId());
            if (runnerId != null) {
                transaction.setRunnerId(runnerId);
                transaction = transactionRepository.save(transaction);
            }
        }

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

        try {
            UUID variantId = locks.isEmpty() ? null : locks.get(0).getVariantId();
            String sku = null;
            String productTitle = null;
            String productImageUrl = null;
            UUID productId = null;
            if (variantId != null) {
                Optional<ProductVariant> vOpt = productVariantRepository.findByIdWithProduct(variantId);
                if (vOpt.isPresent()) {
                    ProductVariant v = vOpt.get();
                    sku = v.getSku();
                    productId = v.getProductId();
                    if (v.getImageUrl() != null && !v.getImageUrl().isBlank()) productImageUrl = v.getImageUrl();
                    if (v.getProduct() != null) {
                        productTitle = v.getProduct().getTitle();
                        if ((productImageUrl == null || productImageUrl.isBlank())
                                && v.getProduct().getPrimaryImageUrl() != null) {
                            productImageUrl = v.getProduct().getPrimaryImageUrl();
                        }
                    }
                }
            }
            BigDecimal price = BigDecimal.valueOf(totalCents).setScale(2, RoundingMode.UNNECESSARY)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
            eventBroadcaster.broadcast(TxEvent.builder()
                    .type(TxEventType.PAID)
                    .createdAt(now)
                    .transactionId(transaction.getId())
                    .storeId(fulfillingStore.getId())
                    .fulfillingStoreId(fulfillingStore.getId())
                    .originatingStoreId(originatingStore.getId())
                    .variantId(variantId)
                    .productId(productId)
                    .productTitle(productTitle)
                    .productImageUrl(productImageUrl)
                    .sku(sku)
                    .retailPrice(price)
                    .currency(txCurrencyUpper)
                    .expiresAt(qrExpiresAt)
                    .qrFallbackCode(fallbackCode)
                    .runnerId(runnerId != null ? runnerId.toString() : null)
                    .status(TransactionStatus.PAID.name())
                    .message("Customer completed payment. Ready for in-store pickup.")
                    .build());
        } catch (Exception ex) {
            log.warn("CheckoutService: broadcast PAID failed txId={}", transactionId, ex);
        }

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
            Store fulfillingStore,
            String currencyLower) {

        String description = "Linked-Store Purchase | tx_" + request.getTransactionId();
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .provider(provider.providerId())
                .paymentMethodId(request.getPaymentMethodId())
                .customerEmail(request.getCustomerEmail())
                .idempotencyKey(request.getIdempotencyKey())
                .amountCents(totalCents)
                .currency(currencyLower != null ? currencyLower : "usd")
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

    /**
     * Payment Flow 2 settlement — executed once the product is picked up.
     *
     * Funds were captured and held on the platform account at checkout. Here we execute two
     * distinct transfers sharing the transaction's {@code transfer_group}:
     * <ul>
     *   <li>Transfer A: wholesale payout → product owner (fulfilling store)</li>
     *   <li>Transfer B: arbitrage margin minus platform fee → brokerage (originating store)</li>
     * </ul>
     * The platform fee (FEE_BASED mode) is the residual that stays on the platform account.
     */
    @Transactional
    public PickupSettlement settleOnPickup(Transaction tx) {
        if (tx == null) {
            return new PickupSettlement(null, null, 0, 0, 0, 0);
        }
        final Store originating = tx.getOriginatingStoreId() != null
                ? storeRepository.findById(tx.getOriginatingStoreId()).orElse(null) : null;
        final Store fulfilling = tx.getFulfillingStoreId() != null
                ? storeRepository.findById(tx.getFulfillingStoreId()).orElse(null) : null;
        if (originating == null || fulfilling == null) {
            log.warn("settleOnPickup: missing store(s) for tx={}, skipping settlement", tx.getId());
            return new PickupSettlement(null, null, 0, 0, 0, 0);
        }

        final String currencyLower = resolveStoreCurrencyLower(fulfilling);
        final int wholesaleCents = tx.getWholesalePayoutCents() != null ? tx.getWholesalePayoutCents() : 0;
        final int marginCents = tx.getArbitrageMarginCents() != null ? tx.getArbitrageMarginCents() : 0;
        final int platformFeeCents = platformContractService.isFeeBased()
                ? platformContractService.computePlatformFeeCents(marginCents)
                : 0;
        final int marginToOriginatingCents = Math.max(0, marginCents - platformFeeCents);

        final String transferGroup = "tx_" + tx.getId().toString();
        final String sourceTransaction = resolveChargeIdForSettlement(tx.getStripePaymentIntentId());
        final PaymentProvider provider = paymentProviderFactory.defaultProvider();

        String wholesaleTransferId = null;
        if (wholesaleCents > 0 && isUsableConnectId(fulfilling.getStripeConnectId())) {
            try {
                final PayoutResponse r = provider.payout(PayoutRequest.builder()
                        .provider(provider.providerId())
                        .amountCents(wholesaleCents)
                        .currency(currencyLower)
                        .destinationAccountId(fulfilling.getStripeConnectId())
                        .transferGroup(transferGroup)
                        .route("WHOLESALE_TO_FULFILLING")
                        .sourceTransaction(sourceTransaction)
                        .build());
                wholesaleTransferId = r.getPayoutId();
                log.info("settleOnPickup: wholesale transfer {} amount={}c dest={} tx={}",
                        wholesaleTransferId, wholesaleCents, fulfilling.getStripeConnectId(), tx.getId());
            } catch (Exception e) {
                log.error("settleOnPickup: wholesale transfer failed tx={} dest={}",
                        tx.getId(), fulfilling.getStripeConnectId(), e);
            }
        } else {
            log.warn("settleOnPickup: skipping wholesale transfer tx={} (amount={}c, connectId={})",
                    tx.getId(), wholesaleCents, fulfilling.getStripeConnectId());
        }

        String marginTransferId = null;
        if (marginToOriginatingCents > 0 && isUsableConnectId(originating.getStripeConnectId())) {
            try {
                final PayoutResponse r = provider.payout(PayoutRequest.builder()
                        .provider(provider.providerId())
                        .amountCents(marginToOriginatingCents)
                        .currency(currencyLower)
                        .destinationAccountId(originating.getStripeConnectId())
                        .transferGroup(transferGroup)
                        .route("MARGIN_TO_ORIGINATING")
                        .sourceTransaction(sourceTransaction)
                        .build());
                marginTransferId = r.getPayoutId();
                log.info("settleOnPickup: margin transfer {} amount={}c dest={} tx={}",
                        marginTransferId, marginToOriginatingCents, originating.getStripeConnectId(), tx.getId());
            } catch (Exception e) {
                log.error("settleOnPickup: margin transfer failed tx={} dest={}",
                        tx.getId(), originating.getStripeConnectId(), e);
            }
        } else {
            log.warn("settleOnPickup: skipping margin transfer tx={} (amount={}c, connectId={})",
                    tx.getId(), marginToOriginatingCents, originating.getStripeConnectId());
        }

        log.info("settleOnPickup complete tx={} group={}: wholesale={}c, marginToOriginating={}c, platformFee={}c",
                tx.getId(), transferGroup, wholesaleCents, marginToOriginatingCents, platformFeeCents);

        return new PickupSettlement(
                wholesaleTransferId, marginTransferId,
                wholesaleCents, marginToOriginatingCents, platformFeeCents, marginCents);
    }

    private static boolean isUsableConnectId(String connectId) {
        return connectId != null && !connectId.isBlank() && !connectId.startsWith("acct_connected_");
    }

    /**
     * Resolves the charge backing the payment so the settlement transfers can draw from the
     * (possibly still pending) funds of that specific charge. Returns null when unavailable, in
     * which case Stripe falls back to the platform's available balance.
     */
    private String resolveChargeIdForSettlement(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) return null;
        try {
            if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
                Stripe.apiKey = stripeConfig.getStripeApiKey();
            }
            final PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
            return pi.getLatestCharge();
        } catch (Exception e) {
            log.warn("settleOnPickup: could not resolve charge for paymentIntent={} ({})",
                    paymentIntentId, e.getMessage());
            return null;
        }
    }

    /** Result of a pickup-time settlement. */
    public record PickupSettlement(
            String wholesaleTransferId,
            String marginTransferId,
            int wholesaleCents,
            int marginToOriginatingCents,
            int platformFeeCents,
            int arbitrageMarginCents) {}

    /**
     * Pick a runner for handoff. Pickup happens at the FULFILLING store so we always prefer
     * the fulfilling store's RUNNER users first, then fall back to originating store or other
     * roles.
     */
    private UUID assignRunnerForPickup(UUID originatingStoreId, UUID fulfillingStoreId) {
        UUID[] order = new UUID[]{fulfillingStoreId, originatingStoreId};
        for (UUID storeId : order) {
            if (storeId == null) continue;
            List<StoreUser> runners = storeUserRepository.findByStoreIdAndRole(storeId, StoreUserRole.RUNNER);
            if (!runners.isEmpty()) return runners.get(0).getId();
        }
        for (UUID storeId : order) {
            if (storeId == null) continue;
            List<StoreUser> owners = storeUserRepository.findByStoreIdAndRole(storeId, StoreUserRole.OWNER);
            if (!owners.isEmpty()) return owners.get(0).getId();
            List<StoreUser> admins = storeUserRepository.findByStoreIdAndRole(storeId, StoreUserRole.STORE_ADMIN);
            if (!admins.isEmpty()) return admins.get(0).getId();
            List<StoreUser> reps = storeUserRepository.findByStoreIdAndRole(storeId, StoreUserRole.STORE_REPRESENTATIVE);
            if (!reps.isEmpty()) return reps.get(0).getId();
            List<StoreUser> clerks = storeUserRepository.findByStoreIdAndRole(storeId, StoreUserRole.CLERK);
            if (!clerks.isEmpty()) return clerks.get(0).getId();
            List<StoreUser> fallback = storeUserRepository.findByStoreId(storeId);
            if (!fallback.isEmpty()) return fallback.get(0).getId();
        }
        return null;
    }

    private UUID assignRunnerForOriginatingStore(UUID originatingStoreId) {
        UUID r = assignRunnerForPickup(originatingStoreId, null);
        if (r != null) return r;
        throw new ResourceNotFoundException("StoreUser for Store", originatingStoreId.toString());
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

    @Transactional
    public void finalizeTransactionPaidAfterStripe(UUID transactionId, String paymentIntentId) {
        final Transaction tx = transactionRepository.findById(transactionId).orElse(null);
        if (tx == null) {
            log.warn("finalizePaidAfterStripe: tx {} not found, skipping", transactionId);
            return;
        }
        if (tx.getStatus() != TransactionStatus.PAID && tx.getStatus() != TransactionStatus.PICKED_UP) {
            tx.setStatus(TransactionStatus.PAID);
        }
        if ((tx.getStripePaymentIntentId() == null || tx.getStripePaymentIntentId().isBlank()) && paymentIntentId != null && !paymentIntentId.isBlank()) {
            tx.setStripePaymentIntentId(paymentIntentId);
            transactionRepository.save(tx);
        } else if (tx.getStatus() == TransactionStatus.PAID) {
            transactionRepository.save(tx);
        }

        final OffsetDateTime now = OffsetDateTime.now();
        final List<InventoryLock> locks = inventoryLockRepository.findByTransactionId(transactionId);
        for (InventoryLock lock : locks) {
            if (lock.getStatus() == InventoryLockStatus.HELD) {
                inventoryLockRepository.updateStatusIfCurrentStatusIs(
                        lock.getId(), InventoryLockStatus.RELEASED_TO_SALE, InventoryLockStatus.HELD);
            }
        }

        final UUID origStoreId = tx.getOriginatingStoreId();
        final UUID fulfillStoreId = tx.getFulfillingStoreId();
        final Store originatingStore = origStoreId != null ? storeRepository.findById(origStoreId).orElse(null) : null;
        final Store fulfillingStore = fulfillStoreId != null ? storeRepository.findById(fulfillStoreId).orElse(null) : null;
        final String txCurrencyLower = resolveStoreCurrencyLower(fulfillingStore);
        final String txCurrencyUpper = txCurrencyLower.toUpperCase(Locale.ROOT);

        // Payment Flow 2 — funds stay on the platform until pickup; no transfers happen here.

        UUID runnerId = tx.getRunnerId();
        if (runnerId == null && originatingStore != null && fulfillingStore != null) {
            runnerId = assignRunnerForPickup(originatingStore.getId(), fulfillingStore.getId());
            if (runnerId != null) {
                tx.setRunnerId(runnerId);
                transactionRepository.save(tx);
            }
        }

        final Optional<QrToken> existingQr = qrTokenRepository.findByTransactionId(transactionId);
        QrToken qrToken;
        if (existingQr != null && existingQr.isPresent()) {
            qrToken = existingQr.get();
        } else {
            final UUID finalRunnerId = runnerId;
            final String secureToken = generateSecureToken(transactionId, finalRunnerId, now);
            final String fallbackCode = generateFallbackCode();
            final OffsetDateTime qrExpiresAt = now.plusMinutes(QR_TOKEN_TTL_MINUTES);
            qrToken = QrToken.builder()
                    .transactionId(transactionId)
                    .runnerId(finalRunnerId)
                    .secureToken(secureToken)
                    .fallbackCode(fallbackCode)
                    .expiresAt(qrExpiresAt)
                    .build();
            qrToken = qrTokenRepository.save(qrToken);
        }

        UUID variantId = null;
        if (!locks.isEmpty()) variantId = locks.get(0).getVariantId();
        else {
            final List<TransactionItem> items = transactionItemRepository.findByTransactionId(transactionId);
            if (!items.isEmpty()) variantId = items.get(0).getVariantId();
        }
        if (variantId == null) {
            log.info("finalizePaidAfterStripe: tx {} no variant/item info, skipping SSE broadcast", transactionId);
            return;
        }
        final UUID pvId = variantId;
        final QrToken finalQrToken = qrToken;
        final UUID finalRunnerId2 = runnerId;
        try {
            String sku = null;
            String productTitle = null;
            String productImageUrl = null;
            UUID productId = null;
            final Optional<ProductVariant> vOpt = productVariantRepository.findByIdWithProduct(pvId);
            if (vOpt.isPresent()) {
                final ProductVariant v = vOpt.get();
                sku = v.getSku();
                productId = v.getProductId();
                if (v.getImageUrl() != null && !v.getImageUrl().isBlank()) productImageUrl = v.getImageUrl();
                if (v.getProduct() != null) {
                    productTitle = v.getProduct().getTitle();
                    if ((productImageUrl == null || productImageUrl.isBlank())
                            && v.getProduct().getPrimaryImageUrl() != null) {
                        productImageUrl = v.getProduct().getPrimaryImageUrl();
                    }
                }
            }
            final int totalCents = tx.getTotalRetailCents() != null ? tx.getTotalRetailCents() : 0;
            final BigDecimal price = BigDecimal.valueOf(totalCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            final UUID fStore = fulfillingStore != null ? fulfillingStore.getId() : null;
            final UUID oStore = originatingStore != null ? originatingStore.getId() : null;
            final String fbCode = finalQrToken.getFallbackCode();
            final OffsetDateTime expiresAt = finalQrToken.getExpiresAt();
            eventBroadcaster.broadcast(TxEvent.builder()
                    .type(TxEventType.PAID)
                    .createdAt(now)
                    .transactionId(transactionId)
                    .storeId(fStore)
                    .fulfillingStoreId(fStore)
                    .originatingStoreId(oStore)
                    .variantId(pvId)
                    .productId(productId)
                    .productTitle(productTitle)
                    .productImageUrl(productImageUrl)
                    .sku(sku)
                    .retailPrice(price)
                    .currency(txCurrencyUpper)
                    .expiresAt(expiresAt)
                    .qrFallbackCode(fbCode)
                    .runnerId(finalRunnerId2 != null ? finalRunnerId2.toString() : null)
                    .status(TransactionStatus.PAID.name())
                    .message("Customer completed payment. Ready for in-store pickup.")
                    .build());
        } catch (Exception ex) {
            log.warn("finalizePaidAfterStripe: broadcast PAID failed txId={}", transactionId, ex);
        }
        log.info("finalizePaidAfterStripe complete: tx={}, pi={}, runner={}, qrTokenId={}",
                transactionId, paymentIntentId, runnerId, finalQrToken.getId());
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
