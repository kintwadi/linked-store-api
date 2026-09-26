package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.CheckoutPayRequest;
import com.vicinity24.core.linkedstore.api.dto.CheckoutPayResponse;
import com.vicinity24.core.linkedstore.api.dto.CheckoutSessionRequest;
import com.vicinity24.core.linkedstore.api.dto.CheckoutSessionResponse;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
import com.vicinity24.core.linkedstore.api.entity.InventoryLock;
import com.vicinity24.core.linkedstore.api.entity.InventoryLockStatus;
import com.vicinity24.core.linkedstore.api.entity.Product;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.QrToken;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.StoreUser;
import com.vicinity24.core.linkedstore.api.entity.StoreUserRole;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.TransactionItem;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.InventoryLockRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.QrTokenRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreUserRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionItemRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.service.CheckoutService;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private static final int QR_TOKEN_TTL_MINUTES = 60;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CheckoutService checkoutService;
    private final StripeConfig stripeConfig;
    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductRepository productRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final QrTokenRepository qrTokenRepository;
    private final StoreUserRepository storeUserRepository;
    private final TransactionEventBroadcaster eventBroadcaster;

    @PostMapping("/sessions")
    public ResponseEntity<CheckoutSessionResponse> createSession(
            @Valid @RequestBody CheckoutSessionRequest request) {

        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }

        if (request.getTransactionId() != null) {
            return createSplitLedgerSession(request);
        }

        return createSingleAccountSession(request);
    }

    private ResponseEntity<CheckoutSessionResponse> createSplitLedgerSession(
            CheckoutSessionRequest request) {

        final Transaction tx = transactionRepository.findById(request.getTransactionId()).orElse(null);
        if (tx == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("Transaction not found.")
                    .build());
        }

        if (tx.getStatus() != TransactionStatus.PENDING_RESERVATION
                && tx.getStatus() != TransactionStatus.RESERVED
                && tx.getStatus() != TransactionStatus.READY
                && tx.getStatus() != TransactionStatus.PAID
                && tx.getStatus() != TransactionStatus.PICKED_UP) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("Transaction is not in a payable state. Status: " + tx.getStatus().name())
                    .build());
        }

        final Store fulfillingStore = storeRepository.findById(tx.getFulfillingStoreId()).orElse(null);
        if (fulfillingStore == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("Fulfilling store not found.")
                    .build());
        }

        final boolean storeNotOnboarded = fulfillingStore.getStripeConnectId() == null
                || fulfillingStore.getStripeConnectId().isBlank()
                || fulfillingStore.getStripeConnectId().startsWith("acct_connected_");

        UUID variantId = null;
        final List<InventoryLock> locks = inventoryLockRepository.findByTransactionId(tx.getId());
        if (!locks.isEmpty()) {
            variantId = locks.get(0).getVariantId();
        } else {
            final List<TransactionItem> items = transactionItemRepository.findByTransactionId(tx.getId());
            if (!items.isEmpty()) {
                variantId = items.get(0).getVariantId();
            }
        }

        ProductVariant variant = null;
        Product product = null;
        if (variantId != null) {
            final Optional<ProductVariant> variantOpt = productVariantRepository.findById(variantId);
            if (variantOpt.isPresent()) {
                variant = variantOpt.get();
                final Optional<Product> productOpt = productRepository.findById(variant.getProductId());
                if (productOpt.isPresent()) {
                    product = productOpt.get();
                }
            }
        }

        String imageUrl = null;
        if (product != null && product.getPrimaryImageUrl() != null && !product.getPrimaryImageUrl().isBlank()) {
            imageUrl = product.getPrimaryImageUrl();
        } else if (variant != null && variant.getImageUrl() != null && !variant.getImageUrl().isBlank()) {
            imageUrl = variant.getImageUrl();
        } else if (request.getPrimaryImageUrl() != null && !request.getPrimaryImageUrl().isBlank()) {
            imageUrl = request.getPrimaryImageUrl();
        }

        final String productTitle = (product != null && product.getTitle() != null)
                ? product.getTitle()
                : (request.getTitle() != null ? request.getTitle() : "Linked-Store Purchase");

        if (storeNotOnboarded) {
            try {
                final List<String> imagesFb = new ArrayList<>();
                if (imageUrl != null && !imageUrl.isBlank()) imagesFb.add(imageUrl);
                final Map<String, Object> productDataFb = new HashMap<>();
                productDataFb.put("name", productTitle);
                if (!imagesFb.isEmpty()) productDataFb.put("images", imagesFb);
                final Map<String, Object> priceDataFb = new HashMap<>();
                priceDataFb.put("currency", "usd");
                priceDataFb.put("unit_amount", tx.getTotalRetailCents());
                priceDataFb.put("product_data", productDataFb);
                final Map<String, Object> lineItemFb = new HashMap<>();
                lineItemFb.put("quantity", 1L);
                lineItemFb.put("price_data", priceDataFb);
                final List<Object> lineItemsFb = new ArrayList<>();
                lineItemsFb.add(lineItemFb);

                final Map<String, String> metaFb = new HashMap<>();
                metaFb.put("transactionId", tx.getId().toString());
                metaFb.put("fulfillingConnectId", fulfillingStore.getStripeConnectId() != null ? fulfillingStore.getStripeConnectId() : "");
                metaFb.put("originatingConnectId",
                        storeRepository.findById(tx.getOriginatingStoreId())
                                .map(s -> s.getStripeConnectId() != null ? s.getStripeConnectId() : "").orElse(""));
                metaFb.put("wholesalePayoutCents", String.valueOf(tx.getWholesalePayoutCents()));
                metaFb.put("arbitrageMarginCents", String.valueOf(tx.getArbitrageMarginCents()));
                metaFb.put("splitFallback", "store-not-onboarded; explicit Transfers will reconcile after custody.");

                final Map<String, Object> paramsFb = new HashMap<>();
                paramsFb.put("mode", SessionCreateParams.Mode.PAYMENT.getValue());
                paramsFb.put("success_url", request.getSuccessUrl());
                paramsFb.put("cancel_url", request.getCancelUrl());
                paramsFb.put("line_items", lineItemsFb);
                paramsFb.put("allow_promotion_codes", Boolean.TRUE);
                paramsFb.put("billing_address_collection", SessionCreateParams.BillingAddressCollection.AUTO.getValue());
                paramsFb.put("metadata", metaFb);
                if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                    paramsFb.put("customer_email", request.getCustomerEmail());
                }

                assertSingleLineItemOnly(paramsFb);
                final Session fallback = Session.create(paramsFb);
                return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                        .id(fallback.getId())
                        .url(fallback.getUrl())
                        .status("ok_fallback_split")
                        .message("Fallback: fulfilling store has not yet onboarded Stripe Connect. " +
                                "Platform will issue explicit wholesale + margin transfers after custody proof.")
                        .build());
            } catch (StripeException fbEx) {
                log.error("Fallback (not-onboarded) checkout also failed for tx={}", request.getTransactionId(), fbEx);
                final String msg = fbEx.getUserMessage() != null ? fbEx.getUserMessage() : fbEx.getMessage();
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message(msg)
                        .build());
            }
        }

        try {
            final List<String> images = new ArrayList<>();
            if (imageUrl != null && !imageUrl.isBlank()) {
                images.add(imageUrl);
            }

            final Map<String, Object> productData = new HashMap<>();
            productData.put("name", productTitle);
            if (!images.isEmpty()) {
                productData.put("images", images);
            }

            final Map<String, Object> priceData = new HashMap<>();
            priceData.put("currency", "usd");
            priceData.put("unit_amount", tx.getTotalRetailCents());
            priceData.put("product_data", productData);

            final Map<String, Object> lineItem = new HashMap<>();
            lineItem.put("quantity", 1L);
            lineItem.put("price_data", priceData);

            final List<Object> lineItems = new ArrayList<>();
            lineItems.add(lineItem);

            final Map<String, Object> transferData = new HashMap<>();
            transferData.put("destination", fulfillingStore.getStripeConnectId());
            transferData.put("amount", tx.getWholesalePayoutCents());

            final Map<String, Object> paymentIntentData = new HashMap<>();
            paymentIntentData.put("transfer_data", transferData);

            final long platformFeeCents = tx.getTotalRetailCents()
                    - tx.getWholesalePayoutCents()
                    - (tx.getArbitrageMarginCents() != null ? tx.getArbitrageMarginCents() : 0L);
            if (platformFeeCents > 0) {
                paymentIntentData.put("application_fee_amount", platformFeeCents);
            }

            final Map<String, String> metadata = new HashMap<>();
            metadata.put("transactionId", tx.getId().toString());

            final Map<String, Object> params = new HashMap<>();
            params.put("mode", SessionCreateParams.Mode.PAYMENT.getValue());
            params.put("success_url", request.getSuccessUrl());
            params.put("cancel_url", request.getCancelUrl());
            params.put("line_items", lineItems);
            params.put("allow_promotion_codes", Boolean.TRUE);
            params.put("billing_address_collection", SessionCreateParams.BillingAddressCollection.AUTO.getValue());
            params.put("payment_intent_data", paymentIntentData);
            params.put("metadata", metadata);

            if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                params.put("customer_email", request.getCustomerEmail());
            }

            assertSingleLineItemOnly(params);
            final Session session = Session.create(params);

            return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                    .id(session.getId())
                    .url(session.getUrl())
                    .status("ok")
                    .build());
        } catch (StripeException ex) {
            String msg = ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage();
            log.warn("Split-ledger checkout create failed for tx={} ({}). Attempting fallback without declarative transfer_data" +
                    " — wholesale & margin will be issued via explicit Transfers after custody proof.", request.getTransactionId(), msg);

            boolean isCapabilityError = false;
            if (msg != null) {
                boolean hasCapabilityMismatch = msg.contains("capabilities")
                        && (msg.contains("transfers") || msg.contains("legacy_payments") || msg.contains("crypto_transfers"));
                boolean hasMissingDestination = msg.contains("No such destination")
                        || msg.contains("resource_missing")
                        || msg.contains("account_not_found")
                        || msg.contains("destination");
                isCapabilityError = hasCapabilityMismatch || hasMissingDestination;
            }

            if (isCapabilityError) {
                try {
                    final List<String> images2 = new ArrayList<>();
                    if (imageUrl != null && !imageUrl.isBlank()) images2.add(imageUrl);
                    final Map<String, Object> productData2 = new HashMap<>();
                    productData2.put("name", productTitle);
                    if (!images2.isEmpty()) productData2.put("images", images2);
                    final Map<String, Object> priceData2 = new HashMap<>();
                    priceData2.put("currency", "usd");
                    priceData2.put("unit_amount", tx.getTotalRetailCents());
                    priceData2.put("product_data", productData2);
                    final Map<String, Object> lineItem2 = new HashMap<>();
                    lineItem2.put("quantity", 1L);
                    lineItem2.put("price_data", priceData2);
                    final List<Object> lineItems2 = new ArrayList<>();
                    lineItems2.add(lineItem2);

                    final Map<String, String> meta2 = new HashMap<>();
                    meta2.put("transactionId", tx.getId().toString());
                    meta2.put("fulfillingConnectId", fulfillingStore.getStripeConnectId());
                    meta2.put("originatingConnectId",
                            storeRepository.findById(tx.getOriginatingStoreId())
                                    .map(s -> s.getStripeConnectId() != null ? s.getStripeConnectId() : "").orElse(""));
                    meta2.put("wholesalePayoutCents", String.valueOf(tx.getWholesalePayoutCents()));
                    meta2.put("arbitrageMarginCents", String.valueOf(tx.getArbitrageMarginCents()));
                    meta2.put("splitFallback", "capability-missing; explicit Transfers will reconcile after custody.");

                    final Map<String, Object> params2 = new HashMap<>();
                    params2.put("mode", SessionCreateParams.Mode.PAYMENT.getValue());
                    params2.put("success_url", request.getSuccessUrl());
                    params2.put("cancel_url", request.getCancelUrl());
                    params2.put("line_items", lineItems2);
                    params2.put("allow_promotion_codes", Boolean.TRUE);
                    params2.put("billing_address_collection", SessionCreateParams.BillingAddressCollection.AUTO.getValue());
                    params2.put("metadata", meta2);
                    if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                        params2.put("customer_email", request.getCustomerEmail());
                    }

                    assertSingleLineItemOnly(params2);
                    final Session fallback = Session.create(params2);
                    return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                            .id(fallback.getId())
                            .url(fallback.getUrl())
                            .status("ok_fallback_split")
                            .message("Fallback: platform will issue explicit wholesale + margin transfers after custody proof " +
                                    "(destination account capability pending). Architecture matches project-context step 8/13 intent.")
                            .build());
                } catch (StripeException fallbackEx) {
                    log.error("Fallback checkout also failed for tx={}", request.getTransactionId(), fallbackEx);
                    msg = fallbackEx.getUserMessage() != null ? fallbackEx.getUserMessage() : fallbackEx.getMessage();
                }
            }

            log.error("Failed creating Stripe Checkout Session for tx={}", request.getTransactionId(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message(msg)
                    .build());
        } catch (Exception ex) {
            log.error("Unexpected error creating split-ledger checkout session for tx={}", request.getTransactionId(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("Could not open payment page. Please try again.")
                    .build());
        }
    }

    private ResponseEntity<CheckoutSessionResponse> createSingleAccountSession(
            CheckoutSessionRequest request) {

        try {
            final UUID variantId = (request.getVariantId() != null && !request.getVariantId().isBlank())
                    ? UUID.fromString(request.getVariantId())
                    : null;
            final UUID productId = (request.getProductId() != null && !request.getProductId().isBlank())
                    ? UUID.fromString(request.getProductId())
                    : null;

            if (productId == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("Product is missing from checkout request. Please try again.")
                        .build());
            }

            final Product product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("Product not found.")
                        .build());
            }

            ProductVariant variant = null;
            if (variantId != null) {
                variant = productVariantRepository.findById(variantId).orElse(null);
            }
            if (variant == null) {
                final List<ProductVariant> available = productVariantRepository.findByProductId(productId);
                variant = available.stream()
                        .filter(v -> v.getStockQuantity() != null && v.getStockQuantity() > 0)
                        .findFirst()
                        .orElse(available.isEmpty() ? null : available.get(0));
                if (variant == null) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(CheckoutSessionResponse.builder()
                            .status("error")
                            .message("No available variant for this product.")
                            .build());
                }
            }

            final UUID fulfillingStoreId = variant.getStoreId();
            final UUID originatingStoreId = (request.getOriginatingStoreId() != null && !request.getOriginatingStoreId().isBlank())
                    ? UUID.fromString(request.getOriginatingStoreId())
                    : variant.getStoreId();

            final int retailCents = Math.max(1, variant.getRetailPriceCents() != null
                    ? variant.getRetailPriceCents()
                    : Math.max(1, request.getAmountCents() == null ? 100 : request.getAmountCents().intValue()));
            final int wholesaleCents = Math.max(1, variant.getWholesalePriceCents() != null
                    ? variant.getWholesalePriceCents()
                    : Math.max(1, (int) Math.round(retailCents * 0.6)));
            final int marginCents = Math.max(0, retailCents - wholesaleCents);

            final Store fulfillingStore = storeRepository.findById(fulfillingStoreId).orElse(null);
            final Store originatingStore = storeRepository.findById(originatingStoreId).orElse(null);
            if (originatingStore == null || fulfillingStore == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("Store configuration missing for split ledger checkout.")
                        .build());
            }

            final Transaction transaction = Transaction.builder()
                    .originatingStoreId(originatingStoreId)
                    .fulfillingStoreId(fulfillingStoreId)
                    .status(TransactionStatus.RESERVED)
                    .totalRetailCents(retailCents)
                    .wholesalePayoutCents(wholesaleCents)
                    .arbitrageMarginCents(marginCents)
                    .build();
            final Transaction saved = transactionRepository.save(transaction);

            try {
                transactionItemRepository.save(TransactionItem.builder()
                        .transactionId(saved.getId())
                        .variantId(variant.getId())
                        .quantity(1)
                        .build());
            } catch (Exception ignore) { /* tx_items are optional for split ledger */ }

            final OffsetDateTime now = OffsetDateTime.now();
            final int countdownSeconds = 900;

            try {
                final InventoryLock lock = new InventoryLock();
                lock.setStoreId(fulfillingStoreId);
                lock.setVariantId(variant.getId());
                lock.setTransactionId(saved.getId());
                lock.setLockedQuantity(1);
                lock.setExpiresAt(now.plusSeconds(countdownSeconds));
                lock.setStatus(InventoryLockStatus.HELD);
                inventoryLockRepository.save(lock);
            } catch (Exception ignore) { /* inventory lock optional for direct checkout */ }

            UUID runnerId = assignRunnerForOriginatingStoreInline(originatingStoreId, fulfillingStoreId);
            if (runnerId != null) {
                saved.setRunnerId(runnerId);
                transactionRepository.save(saved);
            }

            String secureToken = generateSecureTokenInline(saved.getId(), runnerId, now);
            String fallbackCode = generateFallbackCodeInline();
            OffsetDateTime qrExpiresAt = now.plusMinutes(QR_TOKEN_TTL_MINUTES);
            try {
                qrTokenRepository.save(QrToken.builder()
                        .transactionId(saved.getId())
                        .runnerId(runnerId)
                        .secureToken(secureToken)
                        .fallbackCode(fallbackCode)
                        .expiresAt(qrExpiresAt)
                        .build());
            } catch (Exception ignore) { /* qr token optional */ }

            String productImageUrl = product.getPrimaryImageUrl() != null
                    ? product.getPrimaryImageUrl()
                    : variant.getImageUrl();
            OffsetDateTime pickupExpiresAt = now.plusSeconds(countdownSeconds);
            try {
                eventBroadcaster.broadcast(TxEvent.builder()
                        .type(TxEventType.RESERVED)
                        .createdAt(now)
                        .transactionId(saved.getId())
                        .storeId(fulfillingStoreId)
                        .fulfillingStoreId(fulfillingStoreId)
                        .originatingStoreId(originatingStoreId)
                        .variantId(variant.getId())
                        .productId(product.getId())
                        .productTitle(product.getTitle())
                        .productImageUrl(productImageUrl)
                        .sku(variant.getSku())
                        .retailPrice(BigDecimal.valueOf(retailCents)
                                .setScale(2, RoundingMode.UNNECESSARY)
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                        .currency("USD")
                        .expiresAt(pickupExpiresAt)
                        .countdownSeconds(countdownSeconds)
                        .qrFallbackCode(fallbackCode)
                        .runnerId(runnerId != null ? runnerId.toString() : null)
                        .status("RESERVED")
                        .message("Item reserved — 15-minute hold for customer pickup.")
                        .build());
            } catch (Exception ex) {
                log.warn("DirectCheckout: broadcast RESERVED event failed txId={}", saved.getId(), ex);
            }

            final List<String> images = new ArrayList<>();
            String title = request.getTitle();
            if (title == null || title.isBlank()) title = product.getTitle();
            String imageUrl = request.getPrimaryImageUrl();
            if ((imageUrl == null || imageUrl.isBlank()) && variant.getImageUrl() != null) {
                imageUrl = variant.getImageUrl();
            }
            if ((imageUrl == null || imageUrl.isBlank()) && product.getPrimaryImageUrl() != null) {
                imageUrl = product.getPrimaryImageUrl();
            }
            if (imageUrl != null && !imageUrl.isBlank()) images.add(imageUrl);

            final String currency = "USD";

            final Map<String, Object> productData = new HashMap<>();
            productData.put("name", title);
            if (!images.isEmpty()) productData.put("images", images);

            final Map<String, Object> priceData = new HashMap<>();
            priceData.put("currency", currency.toLowerCase());
            priceData.put("unit_amount", retailCents);
            priceData.put("product_data", productData);

            final Map<String, Object> lineItem = new HashMap<>();
            lineItem.put("quantity", 1L);
            lineItem.put("price_data", priceData);

            final List<Object> lineItems = new ArrayList<>();
            lineItems.add(lineItem);

            final Map<String, Object> params = new HashMap<>();
            params.put("mode", SessionCreateParams.Mode.PAYMENT.getValue());
            params.put("success_url", request.getSuccessUrl());
            params.put("cancel_url", request.getCancelUrl());
            params.put("line_items", lineItems);
            params.put("allow_promotion_codes", Boolean.TRUE);
            params.put("billing_address_collection", SessionCreateParams.BillingAddressCollection.AUTO.getValue());

            if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                params.put("customer_email", request.getCustomerEmail());
            }

            final String fulfillConnectId = fulfillingStore.getStripeConnectId();
            final boolean transfersCapable = fulfillConnectId != null
                    && !fulfillConnectId.isBlank()
                    && !fulfillConnectId.startsWith("acct_connected_");

            final Map<String, String> metadata = new HashMap<>();
            metadata.put("transactionId", saved.getId().toString());
            metadata.put("productId", productId.toString());
            metadata.put("variantId", variant.getId().toString());
            metadata.put("originatingStoreId", originatingStoreId.toString());
            metadata.put("fulfillingStoreId", fulfillingStoreId.toString());
            metadata.put("wholesalePayoutCents", String.valueOf(wholesaleCents));
            metadata.put("arbitrageMarginCents", String.valueOf(marginCents));

            if (transfersCapable) {
                try {
                    final int platformFeeCents = Math.max(0, retailCents - wholesaleCents - marginCents);
                    final int transferCap = Math.max(0, retailCents - platformFeeCents);
                    final int realTransfer = Math.min(wholesaleCents, transferCap);
                    if (realTransfer <= 0 || wholesaleCents > retailCents) {
                        throw new IllegalStateException("Wholesale payout exceeds retail amount — using custody fallback.");
                    }
                    final Map<String, Object> transferData = new HashMap<>();
                    transferData.put("destination", fulfillConnectId);
                    transferData.put("amount", realTransfer);
                    final Map<String, Object> paymentIntentData = new HashMap<>();
                    paymentIntentData.put("transfer_data", transferData);
                    if (platformFeeCents > 0) {
                        paymentIntentData.put("application_fee_amount", platformFeeCents);
                    }
                    params.put("payment_intent_data", paymentIntentData);
                    params.put("metadata", metadata);
                    assertSingleLineItemOnly(params);
                    final Session session = Session.create(params);
                    return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                            .id(session.getId())
                            .url(session.getUrl())
                            .status("ok")
                            .build());
                } catch (StripeException | IllegalStateException primaryEx) {
                    final String msg = (primaryEx instanceof StripeException sex)
                            ? (sex.getUserMessage() != null ? sex.getUserMessage() : sex.getMessage())
                            : primaryEx.getMessage();
                    log.warn("Primary direct checkout split transfer failed for product {} ({}). " +
                            "Falling back to custody-style payout after payment.", productId, msg);
                    metadata.put("splitFallback", "capability-or-amount-error; explicit Transfers will reconcile after custody proof.");
                    metadata.put("fulfillingConnectId", fulfillConnectId);
                    metadata.put("originatingConnectId",
                            storeRepository.findById(originatingStoreId)
                                    .map(s -> s.getStripeConnectId() != null ? s.getStripeConnectId() : "")
                                    .orElse(""));
                }
            }

            params.put("metadata", metadata);
            assertSingleLineItemOnly(params);
            final Session session = Session.create(params);

            return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                    .id(session.getId())
                    .url(session.getUrl())
                    .status(transfersCapable ? "ok_fallback_split" : "ok")
                    .message(transfersCapable
                            ? "Fallback: declarative split transfer unavailable for destination account. " +
                            "Platform will issue explicit wholesale + margin transfers after payment custody."
                            : null)
                    .build());
        } catch (IllegalArgumentException iae) {
            log.error("Invalid UUID in checkout request for productId={}", request.getProductId(), iae);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("Invalid product or store identifier.")
                    .build());
        } catch (StripeException ex) {
            log.error("Failed creating Stripe Checkout Session for productId={}", request.getProductId(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message(ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage())
                    .build());
        } catch (Exception ex) {
            log.error("Unexpected error creating checkout session for productId={}", request.getProductId(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("Could not open payment page. Please try again.")
                    .build());
        }
    }

    @PostMapping("/pay")
    public ResponseEntity<CheckoutPayResponse> pay(
            @Valid @RequestBody CheckoutPayRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader) {

        if (idempotencyHeader != null && !idempotencyHeader.isBlank()
                && (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())) {
            request.setIdempotencyKey(idempotencyHeader);
        }

        log.info("Checkout payment requested: tx={}, method={}",
                request.getTransactionId(),
                maskPaymentMethod(request.getPaymentMethodId()));

        CheckoutPayResponse response = checkoutService.processPayment(request);

        log.info("Checkout payment complete: tx={}, pi={}, qrTokenId={}",
                response.getTransactionId(),
                response.getStripePaymentIntentId(),
                response.getQrTokenId());

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    public record ConfirmSessionPaidRequest(
            @jakarta.validation.constraints.NotBlank String sessionId
    ) {}

    public record ConfirmSessionPaidResponse(
            String status,
            String message,
            String transactionId,
            String transactionStatus,
            String stripePaymentIntentId,
            boolean finalized
    ) {}

    @PostMapping("/confirm-session-paid")
    public ResponseEntity<ConfirmSessionPaidResponse> confirmSessionPaid(
            @Valid @RequestBody ConfirmSessionPaidRequest request) {
        final String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ConfirmSessionPaidResponse("error", "sessionId is required", null, null, null, false));
        }
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }
        try {
            final Session session = Session.retrieve(sessionId);
            if (session == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ConfirmSessionPaidResponse("error", "Stripe session not found", null, null, null, false));
            }
            final String paymentStatus = session.getPaymentStatus();
            final String sessionStatus = session.getStatus();
            final boolean paid = "paid".equalsIgnoreCase(paymentStatus)
                    || "complete".equalsIgnoreCase(sessionStatus);
            if (!paid) {
                return ResponseEntity.status(HttpStatus.OK).body(
                        new ConfirmSessionPaidResponse("not_paid",
                                "Stripe session is not yet paid: paymentStatus=" + paymentStatus + " status=" + sessionStatus,
                                null, null, session.getPaymentIntent(), false));
            }
            final Map<String, String> meta = session.getMetadata();
            final String txIdRaw = meta != null ? meta.get("transactionId") : null;
            if (txIdRaw == null || txIdRaw.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                        new ConfirmSessionPaidResponse("error",
                                "Stripe session metadata missing transactionId; cannot finalize",
                                null, null, session.getPaymentIntent(), false));
            }
            final UUID txId;
            try { txId = UUID.fromString(txIdRaw); }
            catch (IllegalArgumentException iae) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                        new ConfirmSessionPaidResponse("error", "Invalid transactionId in session metadata",
                                null, null, session.getPaymentIntent(), false));
            }
            final boolean explicitPayouts = meta != null
                    && (meta.get("splitFallback") != null
                        || "true".equalsIgnoreCase(meta.get("splitFallback"))
                        || meta.get("fulfillingConnectId") != null);
            checkoutService.finalizeTransactionPaidAfterStripe(txId, session.getPaymentIntent(), explicitPayouts);
            final Transaction tx = transactionRepository.findById(txId).orElse(null);
            return ResponseEntity.status(HttpStatus.OK).body(
                    new ConfirmSessionPaidResponse("ok", "Session confirmed finalized to PAID.",
                            tx != null ? tx.getId().toString() : txIdRaw,
                            tx != null && tx.getStatus() != null ? tx.getStatus().name() : TransactionStatus.PAID.name(),
                            session.getPaymentIntent(),
                            true));
        } catch (StripeException se) {
            log.error("CheckoutController.confirmSessionPaid Stripe error session={}", sessionId, se);
            final int code = se.getStatusCode() != null ? se.getStatusCode() : 502;
            return ResponseEntity.status(code).body(
                    new ConfirmSessionPaidResponse("error",
                            "Stripe error: " + (se.getMessage() == null ? se.toString() : se.getMessage()),
                            null, null, null, false));
        } catch (Exception ex) {
            log.error("CheckoutController.confirmSessionPaid failed session={}", sessionId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ConfirmSessionPaidResponse("error",
                            "Internal error: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                            null, null, null, false));
        }
    }

    private String maskPaymentMethod(String pmId) {
        if (pmId == null) {
            return null;
        }
        if (pmId.length() <= 8) {
            return "***";
        }
        return pmId.substring(0, 4) + "_****_" + pmId.substring(pmId.length() - 4);
    }

    private UUID assignRunnerForOriginatingStoreInline(UUID originatingStoreId, UUID fulfillingStoreId) {
        if (fulfillingStoreId != null) {
            List<StoreUser> fulfillRunners = storeUserRepository.findByStoreIdAndRole(
                    fulfillingStoreId, StoreUserRole.RUNNER);
            if (!fulfillRunners.isEmpty()) {
                return fulfillRunners.get(0).getId();
            }
        }
        if (originatingStoreId != null) {
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
            List<StoreUser> storeAdmins = storeUserRepository.findByStoreIdAndRole(
                    originatingStoreId, StoreUserRole.STORE_ADMIN);
            if (!storeAdmins.isEmpty()) {
                return storeAdmins.get(0).getId();
            }
            List<StoreUser> representatives = storeUserRepository.findByStoreIdAndRole(
                    originatingStoreId, StoreUserRole.STORE_REPRESENTATIVE);
            if (!representatives.isEmpty()) {
                return representatives.get(0).getId();
            }
            List<StoreUser> clerks = storeUserRepository.findByStoreIdAndRole(
                    originatingStoreId, StoreUserRole.CLERK);
            if (!clerks.isEmpty()) {
                return clerks.get(0).getId();
            }
            List<StoreUser> fallback = storeUserRepository.findByStoreId(originatingStoreId);
            if (!fallback.isEmpty()) {
                return fallback.get(0).getId();
            }
        }
        return null;
    }

    private String generateSecureTokenInline(UUID txId, UUID runnerId, OffsetDateTime now) {
        final String base = (txId != null ? txId.toString() : UUID.randomUUID().toString())
                + "|" + (runnerId != null ? runnerId.toString() : "no-runner")
                + "|" + (now != null ? now.toString() : OffsetDateTime.now().toString())
                + "|" + SECURE_RANDOM.nextLong();
        try {
            final byte[] raw = java.nio.charset.StandardCharsets.UTF_8.encode(base).array();
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
            return "ls_" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Arrays.copyOfRange(digest, 0, 12));
        } catch (java.security.NoSuchAlgorithmException nsae) {
            throw new RuntimeException("SHA-256 not available on JVM", nsae);
        }
    }

    private String generateFallbackCodeInline() {
        final StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private static void assertSingleLineItemOnly(Map<String, Object> params) {
        final Object raw = params.get("line_items");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("Stripe CheckoutSession line_items must be a list. Actual: "
                    + (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        if (list.size() != 1) {
            throw new IllegalStateException("Stripe CheckoutSession must contain exactly 1 line item (retail total only; " +
                    "wholesale/margin are post-capture transfers never surfaced to the customer). Actual size = " + list.size());
        }
        final Object first = list.get(0);
        if (!(first instanceof Map<?, ?> li)) {
            throw new IllegalStateException("Stripe CheckoutSession line_item[0] must be a Map. Got: "
                    + (first == null ? "null" : first.getClass()));
        }
        final Object qty = li.get("quantity");
        if (!(qty instanceof Number n) || n.longValue() != 1L) {
            throw new IllegalStateException("Stripe CheckoutSession line_item quantity must be 1 (no split add-ons). Quantity = " + qty);
        }
    }
}
