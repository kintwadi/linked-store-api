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
import com.vicinity24.core.linkedstore.api.service.PlatformContractService;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    static String resolveStoreCurrencyLower(Store store) {
        if (store == null) return "usd";
        String c = store.getCurrencyCode();
        if (c == null || c.isBlank()) return "usd";
        return c.trim().toLowerCase(Locale.ROOT);
    }

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
    private final PlatformContractService platformContractService;

    private static String appendQueryParam(String url, String name, String value) {
        if (url == null || url.isBlank() || name == null || name.isBlank() || value == null || value.isBlank()) {
            return url;
        }
        String base = url;
        String fragment = "";
        int hashIdx = url.indexOf('#');
        if (hashIdx >= 0) {
            base = url.substring(0, hashIdx);
            fragment = url.substring(hashIdx);
        }
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return base + (base.contains("?") ? "&" : "?") + name + "=" + encoded + fragment;
    }

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

        final Store originatingStore = storeRepository.findById(tx.getOriginatingStoreId()).orElse(null);
        final String txCurrencyLower = resolveStoreCurrencyLower(fulfillingStore);
        final String txCurrencyUpper = txCurrencyLower.toUpperCase(Locale.ROOT);

        if (!platformContractService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("The platform contract is not configured yet. Please contact support.")
                    .build());
        }
        if (!platformContractService.canStoreTransact(originatingStore)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("The originating store does not have an active subscription.")
                    .build());
        }
        if (!platformContractService.canStoreTransact(fulfillingStore)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(CheckoutSessionResponse.builder()
                    .status("error")
                    .message("The fulfilling store does not have an active subscription.")
                    .build());
        }

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
            priceData.put("currency", txCurrencyLower);
            priceData.put("unit_amount", tx.getTotalRetailCents());
            priceData.put("product_data", productData);

            final Map<String, Object> lineItem = new HashMap<>();
            lineItem.put("quantity", 1L);
            lineItem.put("price_data", priceData);

            final List<Object> lineItems = new ArrayList<>();
            lineItems.add(lineItem);

            // Payment Flow 2 — Hold on Platform & Split at Product Pickup.
            // The full retail amount is captured and held on the platform account. No declarative
            // transfer_data / application_fee is attached here. Wholesale (product owner) and
            // margin (broker, minus platform fee) are transferred explicitly at pickup.
            final Map<String, String> metadata = new HashMap<>();
            metadata.put("transactionId", tx.getId().toString());
            metadata.put("wholesalePayoutCents", String.valueOf(tx.getWholesalePayoutCents()));
            metadata.put("arbitrageMarginCents", String.valueOf(tx.getArbitrageMarginCents()));
            metadata.put("fulfillingConnectId",
                    fulfillingStore.getStripeConnectId() != null ? fulfillingStore.getStripeConnectId() : "");
            metadata.put("originatingConnectId",
                    storeRepository.findById(tx.getOriginatingStoreId())
                            .map(s -> s.getStripeConnectId() != null ? s.getStripeConnectId() : "").orElse(""));
            metadata.put("settlementMode", "hold-on-platform");

            final Map<String, Object> params = new HashMap<>();
            params.put("mode", SessionCreateParams.Mode.PAYMENT.getValue());
            params.put("success_url", request.getSuccessUrl());
            params.put("cancel_url", appendQueryParam(request.getCancelUrl(), "txId", tx.getId().toString()));
            params.put("line_items", lineItems);
            params.put("allow_promotion_codes", Boolean.TRUE);
            params.put("billing_address_collection", SessionCreateParams.BillingAddressCollection.AUTO.getValue());
            params.put("metadata", metadata);

            // Shared transfer_group across the charge and the pickup-time transfers so the whole
            // lifecycle can be reconciled (and reversed) as one group.
            final Map<String, Object> paymentIntentData = new HashMap<>();
            paymentIntentData.put("transfer_group", "tx_" + tx.getId().toString());
            params.put("payment_intent_data", paymentIntentData);

            if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                params.put("customer_email", request.getCustomerEmail());
            }

            assertSingleLineItemOnly(params);
            final Session session = Session.create(params);

            return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                    .id(session.getId())
                    .url(session.getUrl())
                    .status("ok")
                    .message("Funds held on platform; wholesale and margin transfers execute at pickup.")
                    .build());
        } catch (StripeException ex) {
            log.error("Failed creating Stripe Checkout Session for tx={}", request.getTransactionId(), ex);
            final String msg = ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage();
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

            if (originatingStoreId.equals(fulfillingStoreId)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("Self-store checkout is not allowed. Select a different store to complete the purchase.")
                        .build());
            }

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

            if (!platformContractService.isConfigured()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("The platform contract is not configured yet. Please contact support.")
                        .build());
            }
            if (!platformContractService.canStoreTransact(originatingStore)) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("The originating store does not have an active subscription.")
                        .build());
            }
            if (!platformContractService.canStoreTransact(fulfillingStore)) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(CheckoutSessionResponse.builder()
                        .status("error")
                        .message("The fulfilling store does not have an active subscription.")
                        .build());
            }

            final String txCurrencyLower = resolveStoreCurrencyLower(fulfillingStore);
            final String txCurrencyUpper = txCurrencyLower.toUpperCase(Locale.ROOT);

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
                        .currency(txCurrencyUpper)
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

            final String currency = txCurrencyUpper;

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
            params.put("cancel_url", appendQueryParam(request.getCancelUrl(), "txId", saved.getId().toString()));
            params.put("line_items", lineItems);
            params.put("allow_promotion_codes", Boolean.TRUE);
            params.put("billing_address_collection", SessionCreateParams.BillingAddressCollection.AUTO.getValue());

            if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                params.put("customer_email", request.getCustomerEmail());
            }

            final Map<String, String> metadata = new HashMap<>();
            metadata.put("transactionId", saved.getId().toString());
            metadata.put("productId", productId.toString());
            metadata.put("variantId", variant.getId().toString());
            metadata.put("originatingStoreId", originatingStoreId.toString());
            metadata.put("fulfillingStoreId", fulfillingStoreId.toString());
            metadata.put("wholesalePayoutCents", String.valueOf(wholesaleCents));
            metadata.put("arbitrageMarginCents", String.valueOf(marginCents));
            metadata.put("fulfillingConnectId",
                    fulfillingStore.getStripeConnectId() != null ? fulfillingStore.getStripeConnectId() : "");
            metadata.put("originatingConnectId",
                    originatingStore.getStripeConnectId() != null ? originatingStore.getStripeConnectId() : "");
            metadata.put("settlementMode", "hold-on-platform");

            // Payment Flow 2 — Hold on Platform & Split at Product Pickup.
            // No declarative transfer_data is attached; the platform holds the full retail amount
            // and executes wholesale + margin transfers explicitly when the product is picked up.
            params.put("metadata", metadata);

            // Shared transfer_group across the charge and the pickup-time transfers.
            final Map<String, Object> paymentIntentData = new HashMap<>();
            paymentIntentData.put("transfer_group", "tx_" + saved.getId().toString());
            params.put("payment_intent_data", paymentIntentData);

            assertSingleLineItemOnly(params);
            final Session session = Session.create(params);

            return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutSessionResponse.builder()
                    .id(session.getId())
                    .url(session.getUrl())
                    .status("ok")
                    .message("Funds held on platform; wholesale and margin transfers execute at pickup.")
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
            checkoutService.finalizeTransactionPaidAfterStripe(txId, session.getPaymentIntent());
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
