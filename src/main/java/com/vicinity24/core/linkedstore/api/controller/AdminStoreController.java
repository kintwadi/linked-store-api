package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.AccountUpdateParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.ConnectOnboardingResponse;
import com.vicinity24.core.linkedstore.api.dto.CreateStoreRequest;
import com.vicinity24.core.linkedstore.api.dto.StoreAdminResponse;
import com.vicinity24.core.linkedstore.api.dto.StoreInventoryListingRequest;
import com.vicinity24.core.linkedstore.api.dto.StoreInventoryListingResponse;
import com.vicinity24.core.linkedstore.api.dto.StoreTransactionResponse;
import com.vicinity24.core.linkedstore.api.dto.UpdateStoreRequest;
import com.vicinity24.core.linkedstore.api.entity.Product;
import com.vicinity24.core.linkedstore.api.entity.ProductStatus;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.VariantStatus;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.ProductRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionItemRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final StoreRepository storeRepository;
    private final UserAccountRepository userAccountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final AuthenticationFacade authenticationFacade;
    private final StripeConfig stripeConfig;

    @Value("${linkedstore.connect.frontend-return-url:http://localhost:4200/admin}")
    private String defaultReturnUrl;

    @Value("${linkedstore.connect.frontend-refresh-url:http://localhost:4200/admin}")
    private String defaultRefreshUrl;

    // ---------- "me" endpoints (for STORE_ADMIN / OWNER / GLOBAL_ADMIN scoped to store) ----------

    @GetMapping("/me")
    public ResponseEntity<?> getMyStore() {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @PutMapping("/me")
    @Transactional
    public ResponseEntity<?> updateMyStore(@Valid @RequestBody UpdateStoreRequest request) {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        applyStoreUpdate(store, request);
        store = storeRepository.save(store);
        log.info("Store admin updated own store id={}", store.getId());
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @PostMapping("/me/connect/onboarding-link")
    @Transactional
    public ResponseEntity<ConnectOnboardingResponse> createMyOnboardingLink(
            @RequestBody(required = false) Map<String, String> body) {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        return createOnboardingLink(store, body);
    }

    @PostMapping("/me/connect/login-link")
    public ResponseEntity<ConnectOnboardingResponse> createMyLoginLink() {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        return createLoginLink(store);
    }

    // ---------- "me" inventory endpoints ----------

    @GetMapping("/me/inventory")
    public ResponseEntity<?> getMyInventory() {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        List<ProductVariant> variants = productVariantRepository.findByStoreId(store.getId());
        List<StoreInventoryListingResponse> result = new ArrayList<>();
        for (ProductVariant pv : variants) {
            if (pv.getStatus() == VariantStatus.INACTIVE) continue;
            result.add(buildInventoryResponse(pv));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/me/inventory")
    @Transactional
    public ResponseEntity<?> createMyInventory(@RequestBody StoreInventoryListingRequest request) {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        ProductVariant variant = createInventoryListing(store.getId(), request);
        log.info("Store admin created inventory variant id={} storeId={}", variant.getId(), store.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(buildInventoryResponse(variant));
    }

    @GetMapping("/me/inventory/{variantId}")
    public ResponseEntity<?> getMyInventoryVariant(@PathVariable("variantId") UUID variantId) {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        ProductVariant variant = productVariantRepository.findByIdAndStoreId(variantId, store.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
        return ResponseEntity.ok(buildInventoryResponse(variant));
    }

    @PutMapping("/me/inventory/{variantId}")
    @Transactional
    public ResponseEntity<?> updateMyInventoryVariant(
            @PathVariable("variantId") UUID variantId,
            @RequestBody StoreInventoryListingRequest request) {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        ProductVariant variant = productVariantRepository.findByIdAndStoreId(variantId, store.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
        applyVariantUpdate(variant, request);
        variant = productVariantRepository.save(variant);
        log.info("Store admin updated inventory variant id={} storeId={}", variant.getId(), store.getId());
        return ResponseEntity.ok(buildInventoryResponse(variant));
    }

    @DeleteMapping("/me/inventory/{variantId}")
    @Transactional
    public ResponseEntity<?> deleteMyInventoryVariant(@PathVariable("variantId") UUID variantId) {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        ProductVariant variant = productVariantRepository.findByIdAndStoreId(variantId, store.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
        variant.setStatus(VariantStatus.INACTIVE);
        productVariantRepository.save(variant);
        log.info("Store admin soft-deleted inventory variant id={} storeId={} (status=INACTIVE)", variantId, store.getId());
        return ResponseEntity.ok(buildInventoryResponse(variant));
    }

    // ---------- "me" transactions endpoint ----------

    @GetMapping("/me/transactions")
    public ResponseEntity<?> getMyTransactions() {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        return ResponseEntity.ok(buildStoreTransactionResponses(store.getId()));
    }

    // ---------- Global-admin CRUD ----------

    @GetMapping("")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    public ResponseEntity<?> listStores() {
        authenticationFacade.requireGlobalAdmin();
        List<Store> stores = storeRepository.findAll();
        List<StoreAdminResponse> result = new ArrayList<>();
        for (Store store : stores) {
            result.add(buildStoreAdminResponse(store));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    public ResponseEntity<?> getStore(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @PostMapping("")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> createStore(@Valid @RequestBody CreateStoreRequest request) {
        authenticationFacade.requireGlobalAdmin();
        SubscriptionStatus subStatus = SubscriptionStatus.ACTIVE;
        if (request.getSubscriptionStatus() != null && !request.getSubscriptionStatus().isBlank()) {
            try { subStatus = SubscriptionStatus.valueOf(request.getSubscriptionStatus()); }
            catch (IllegalArgumentException ignored) { subStatus = SubscriptionStatus.ACTIVE; }
        }
        Store store = Store.builder()
                .businessName(request.getBusinessName())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .stripeConnectId(request.getStripeConnectId())
                .subscriptionStatus(subStatus)
                .logoUrl(request.getLogoUrl())
                .heroImageUrl(request.getHeroImageUrl())
                .build();
        store = storeRepository.save(store);
        log.info("Global admin created store id={} businessName={}",
                store.getId(), store.getBusinessName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(buildStoreAdminResponse(store));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> updateStore(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateStoreRequest request) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        applyStoreUpdate(store, request);
        store = storeRepository.save(store);
        log.info("Global admin updated store id={}", id);
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @PutMapping("/{id}/subscription-status")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> updateSubscriptionStatus(
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Object> body) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        Object val = body.get("subscriptionStatus");
        if (val == null) val = body.get("status");
        if (val == null && body.containsKey("suspended")) {
            boolean suspended = Boolean.TRUE.equals(body.get("suspended"));
            val = suspended ? "SUSPENDED" : "ACTIVE";
        }
        if (val != null) {
            try { store.setSubscriptionStatus(SubscriptionStatus.valueOf(val.toString())); }
            catch (IllegalArgumentException ignored) { /* keep existing */ }
        }
        store = storeRepository.save(store);
        log.info("Admin set store {} subscriptionStatus={}", store.getId(), store.getSubscriptionStatus());
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> deleteStore(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        store.setSubscriptionStatus(SubscriptionStatus.CANCELED);
        store.setActiveSubscription(null);
        storeRepository.save(store);
        log.info("Global admin soft-deleted store id={} (subscriptionStatus=CANCELED)", id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Global admin: Inventory per store by id ----------

    @GetMapping("/{id}/inventory")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    public ResponseEntity<?> getStoreInventory(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        List<ProductVariant> variants = productVariantRepository.findByStoreId(store.getId());
        List<StoreInventoryListingResponse> result = new ArrayList<>();
        for (ProductVariant pv : variants) {
            if (pv.getStatus() == VariantStatus.INACTIVE) continue;
            result.add(buildInventoryResponse(pv));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/inventory")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> createStoreInventory(
            @PathVariable("id") UUID id,
            @RequestBody StoreInventoryListingRequest request) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        ProductVariant variant = createInventoryListing(store.getId(), request);
        log.info("Global admin created inventory variant id={} storeId={}", variant.getId(), store.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(buildInventoryResponse(variant));
    }

    @GetMapping("/{id}/inventory/{variantId}")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    public ResponseEntity<?> getStoreInventoryVariant(
            @PathVariable("id") UUID id,
            @PathVariable("variantId") UUID variantId) {
        authenticationFacade.requireGlobalAdmin();
        storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        ProductVariant variant = productVariantRepository.findByIdAndStoreId(variantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
        return ResponseEntity.ok(buildInventoryResponse(variant));
    }

    @PutMapping("/{id}/inventory/{variantId}")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> updateStoreInventoryVariant(
            @PathVariable("id") UUID id,
            @PathVariable("variantId") UUID variantId,
            @RequestBody StoreInventoryListingRequest request) {
        authenticationFacade.requireGlobalAdmin();
        storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        ProductVariant variant = productVariantRepository.findByIdAndStoreId(variantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
        applyVariantUpdate(variant, request);
        variant = productVariantRepository.save(variant);
        log.info("Global admin updated inventory variant id={} storeId={}", variant.getId(), id);
        return ResponseEntity.ok(buildInventoryResponse(variant));
    }

    @DeleteMapping("/{id}/inventory/{variantId}")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<?> deleteStoreInventoryVariant(
            @PathVariable("id") UUID id,
            @PathVariable("variantId") UUID variantId) {
        authenticationFacade.requireGlobalAdmin();
        storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        ProductVariant variant = productVariantRepository.findByIdAndStoreId(variantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
        variant.setStatus(VariantStatus.INACTIVE);
        productVariantRepository.save(variant);
        log.info("Global admin soft-deleted inventory variant id={} storeId={} (status=INACTIVE)", variantId, id);
        return ResponseEntity.ok(buildInventoryResponse(variant));
    }

    // ---------- Global admin: Transactions per store by id ----------

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    public ResponseEntity<?> getStoreTransactions(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        return ResponseEntity.ok(buildStoreTransactionResponses(store.getId()));
    }

    // ---------- Global admin: Connect per store by id ----------

    @PostMapping("/{id}/connect/onboarding-link")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    @Transactional
    public ResponseEntity<ConnectOnboardingResponse> globalOnboardingLink(
            @PathVariable("id") UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        return createOnboardingLink(store, body);
    }

    @PostMapping("/{id}/connect/login-link")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    public ResponseEntity<ConnectOnboardingResponse> globalLoginLink(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        return createLoginLink(store);
    }

    // ---------- helpers ----------

    private Store resolveMyStore(CurrentUser current) {
        if (current.isGlobalAdmin() && current.getStoreId() == null) {
            return storeRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Store", "<none>"));
        }
        UUID storeId = current.getStoreId();
        if (storeId == null) {
            throw new ResourceNotFoundException("Store", "<not bound>");
        }
        authenticationFacade.requireStoreAdminOrOwner(storeId);
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId.toString()));
    }

    private void applyStoreUpdate(Store store, UpdateStoreRequest request) {
        if (request.getBusinessName() != null) {
            store.setBusinessName(request.getBusinessName());
        }
        if (request.getLatitude() != null) {
            store.setLatitude(request.getLatitude());
        }
        if (request.getLongitude() != null) {
            store.setLongitude(request.getLongitude());
        }
        if (request.getStripeConnectId() != null) {
            store.setStripeConnectId(request.getStripeConnectId());
        }
        if (request.getSubscriptionStatus() != null) {
            try {
                store.setSubscriptionStatus(SubscriptionStatus.valueOf(request.getSubscriptionStatus()));
            } catch (IllegalArgumentException ignored) {
                // keep existing subscription status
            }
        }
        if (request.getLogoUrl() != null) {
            store.setLogoUrl(request.getLogoUrl());
        }
        if (request.getHeroImageUrl() != null) {
            store.setHeroImageUrl(request.getHeroImageUrl());
        }
    }

    private ResponseEntity<ConnectOnboardingResponse> createOnboardingLink(Store store, Map<String, String> body) {
        ensureStripeKey();
        try {
            String stripeConnectId = store.getStripeConnectId();
            boolean isNewAccount = false;
            if (stripeConnectId == null || stripeConnectId.isBlank() || stripeConnectId.startsWith("acct_connected_")) {
                String ownerEmail = (body != null && body.get("ownerEmail") != null)
                        ? body.get("ownerEmail")
                        : ("store-" + store.getId() + "@vicinity24.dev");
                AccountCreateParams.Builder b = AccountCreateParams.builder()
                        .setType(AccountCreateParams.Type.EXPRESS)
                        .setCountry("US")
                        .setEmail(ownerEmail)
                        .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                        .putMetadata("storeId", store.getId().toString())
                        .putMetadata("businessName", store.getBusinessName())
                        .setCapabilities(AccountCreateParams.Capabilities.builder()
                                .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                        .setRequested(true).build())
                                .setCardPayments(AccountCreateParams.Capabilities.CardPayments.builder()
                                        .setRequested(true).build())
                                .build());
                Account account = Account.create(b.build());
                stripeConnectId = account.getId();
                store.setStripeConnectId(stripeConnectId);
                storeRepository.save(store);
                isNewAccount = true;
                log.info("AdminConnect: created new Stripe Account {} for store {}",
                        stripeConnectId, store.getId());
            }
            try {
                Account current = Account.retrieve(stripeConnectId);
                boolean needUpdate = current.getCapabilities() == null
                        || current.getCapabilities().getTransfers() == null
                        || current.getCapabilities().getCardPayments() == null;
                if (needUpdate) {
                    AccountUpdateParams upd = AccountUpdateParams.builder()
                            .setCapabilities(AccountUpdateParams.Capabilities.builder()
                                    .setTransfers(AccountUpdateParams.Capabilities.Transfers.builder()
                                            .setRequested(true).build())
                                    .setCardPayments(AccountUpdateParams.Capabilities.CardPayments.builder()
                                            .setRequested(true).build())
                                    .build())
                            .build();
                    current.update(upd);
                }
            } catch (StripeException capEx) {
                log.warn("AdminConnect: capability ensure failed {}", capEx.getMessage());
            }
            String returnUrl = (body != null && body.get("returnUrl") != null)
                    ? body.get("returnUrl") : defaultReturnUrl;
            String refreshUrl = (body != null && body.get("refreshUrl") != null)
                    ? body.get("refreshUrl") : defaultRefreshUrl;
            AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
                    .setAccount(stripeConnectId)
                    .setRefreshUrl(refreshUrl)
                    .setReturnUrl(returnUrl)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setCollect(AccountLinkCreateParams.Collect.CURRENTLY_DUE)
                    .build();
            try {
                com.stripe.model.AccountLink link = com.stripe.model.AccountLink.create(linkParams);
                return ResponseEntity.status(HttpStatus.CREATED).body(ConnectOnboardingResponse.builder()
                        .url(link.getUrl())
                        .object("account_link")
                        .status(isNewAccount ? "new_account" : "existing_account")
                        .stripeConnectId(stripeConnectId)
                        .storeId(store.getId())
                        .build());
            } catch (StripeException ex) {
                String fallback = "https://dashboard.stripe.com/" + stripeConnectId;
                log.warn("AdminConnect: AccountLink fallback {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.CREATED).body(ConnectOnboardingResponse.builder()
                        .url(fallback)
                        .object("account_link")
                        .status(isNewAccount ? "new_account" : "existing_account")
                        .stripeConnectId(stripeConnectId)
                        .storeId(store.getId())
                        .message("Fallback dashboard access: " + ex.getMessage())
                        .build());
            }
        } catch (StripeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message(ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage())
                    .storeId(store.getId())
                    .build());
        }
    }

    private ResponseEntity<ConnectOnboardingResponse> createLoginLink(Store store) {
        ensureStripeKey();
        if (store.getStripeConnectId() == null || store.getStripeConnectId().isBlank()
                || store.getStripeConnectId().startsWith("acct_connected_")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message("Store is not onboarded. Create onboarding link first.")
                    .storeId(store.getId())
                    .build());
        }
        try {
            Account account = Account.retrieve(store.getStripeConnectId());
            String dashboard = "https://dashboard.stripe.com/" + store.getStripeConnectId();
            return ResponseEntity.ok(ConnectOnboardingResponse.builder()
                    .url(dashboard)
                    .object("login_link")
                    .status("ok")
                    .stripeConnectId(store.getStripeConnectId())
                    .storeId(store.getId())
                    .chargesEnabled(account.getChargesEnabled())
                    .payoutsEnabled(account.getPayoutsEnabled())
                    .build());
        } catch (StripeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message(ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage())
                    .storeId(store.getId())
                    .build());
        }
    }

    private void ensureStripeKey() {
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }
    }

    private StoreAdminResponse buildStoreAdminResponse(Store store) {
        UUID storeId = store.getId();
        long userCount = userAccountRepository.findByStoreId(storeId).size();
        long txOriginating = transactionRepository.findByOriginatingStoreId(storeId).size();
        long txFulfilling = transactionRepository.findByFulfillingStoreId(storeId).size();
        long transactionCount = txOriginating + txFulfilling;
        boolean onboarded = store.getStripeConnectId() != null
                && !store.getStripeConnectId().isBlank()
                && !store.getStripeConnectId().startsWith("acct_connected_");
        return StoreAdminResponse.builder()
                .id(store.getId())
                .businessName(store.getBusinessName())
                .latitude(store.getLatitude())
                .longitude(store.getLongitude())
                .stripeConnectId(store.getStripeConnectId())
                .onboarded(onboarded)
                .subscriptionStatus(store.getSubscriptionStatus() != null
                        ? store.getSubscriptionStatus().name()
                        : null)
                .logoUrl(store.getLogoUrl())
                .heroImageUrl(store.getHeroImageUrl())
                .activeSubscriptionId(store.getActiveSubscription() != null
                        ? store.getActiveSubscription().getId()
                        : null)
                .usersCount((int) userCount)
                .transactionCount((int) transactionCount)
                .createdAt(store.getCreatedAt())
                .build();
    }

    // ---------- inventory helpers ----------

    private ProductVariant createInventoryListing(UUID storeId, StoreInventoryListingRequest request) {
        Product product;
        if (request.getProductId() != null) {
            product = productRepository.findById(request.getProductId())
                    .map(existing -> {
                        boolean dirty = false;
                        if (request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
                            existing.setPrimaryImageUrl(request.getImageUrl());
                            dirty = true;
                        }
                        if (request.getProductGalleryImageUrls() != null) {
                            existing.setGalleryImageUrls(request.getProductGalleryImageUrls());
                            dirty = true;
                        }
                        if (request.getDescription() != null && !request.getDescription().isBlank()) {
                            existing.setDescription(request.getDescription());
                            dirty = true;
                        }
                        if (request.getTitle() != null && !request.getTitle().isBlank()
                                && !request.getTitle().equals(existing.getTitle())) {
                            existing.setTitle(request.getTitle());
                            dirty = true;
                        }
                        return dirty ? productRepository.save(existing) : existing;
                    })
                    .orElseThrow(() -> new ResourceNotFoundException("Product", request.getProductId().toString()));
        } else if (request.getTitle() != null && !request.getTitle().isBlank()) {
            product = productRepository.findByTitle(request.getTitle())
                    .map(existing -> {
                        boolean dirty = false;
                        if (request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
                            existing.setPrimaryImageUrl(request.getImageUrl());
                            dirty = true;
                        }
                        if (request.getProductGalleryImageUrls() != null) {
                            existing.setGalleryImageUrls(request.getProductGalleryImageUrls());
                            dirty = true;
                        }
                        if (request.getDescription() != null && !request.getDescription().isBlank()) {
                            existing.setDescription(request.getDescription());
                            dirty = true;
                        }
                        return dirty ? productRepository.save(existing) : existing;
                    })
                    .orElseGet(() -> {
                        Product newProduct = Product.builder()
                                .title(request.getTitle())
                                .description(request.getDescription())
                                .primaryImageUrl(request.getImageUrl())
                                .galleryImageUrls(request.getProductGalleryImageUrls() != null
                                        ? request.getProductGalleryImageUrls()
                                        : new ArrayList<>())
                                .status(ProductStatus.ACTIVE)
                                .attributes(new HashMap<>())
                                .build();
                        return productRepository.save(newProduct);
                    });
        } else {
            throw new IllegalArgumentException("Either productId or title must be provided.");
        }

        VariantStatus variantStatus = VariantStatus.ACTIVE;
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            try {
                variantStatus = VariantStatus.valueOf(request.getStatus());
            } catch (IllegalArgumentException ignored) {
                variantStatus = VariantStatus.ACTIVE;
            }
        }

        Map<String, Object> variantAttrs = request.getVariantAttributes() != null
                ? request.getVariantAttributes()
                : new HashMap<>();

        String resolvedSku = request.getSku();
        if (resolvedSku != null && resolvedSku.isBlank()) {
            resolvedSku = null;
        }
        if (resolvedSku == null) {
            resolvedSku = "SKU-%s-%s".formatted(
                    storeId.toString().substring(0, 8),
                    Long.toHexString(System.currentTimeMillis() % 0xFFFFFFL)
            ).toUpperCase();
        }

        ProductVariant variant = ProductVariant.builder()
                .productId(product.getId())
                .storeId(storeId)
                .sku(resolvedSku)
                .wholesalePriceCents(request.getWholesalePriceCents())
                .retailPriceCents(request.getRetailPriceCents())
                .stockQuantity(request.getStockQuantity() != null ? request.getStockQuantity() : 0)
                .status(variantStatus)
                .imageUrl(request.getVariantImageUrl())
                .galleryImageUrls(request.getVariantGalleryImageUrls() != null
                        ? request.getVariantGalleryImageUrls()
                        : new ArrayList<>())
                .variantAttributes(variantAttrs)
                .build();

        return productVariantRepository.save(variant);
    }

    private void applyVariantUpdate(ProductVariant variant, StoreInventoryListingRequest request) {
        if (request.getSku() != null) {
            String s = request.getSku().isBlank() ? null : request.getSku();
            variant.setSku(s);
        }
        if (request.getWholesalePriceCents() != null) {
            variant.setWholesalePriceCents(request.getWholesalePriceCents());
        }
        if (request.getRetailPriceCents() != null) {
            variant.setRetailPriceCents(request.getRetailPriceCents());
        }
        if (request.getStockQuantity() != null) {
            variant.setStockQuantity(request.getStockQuantity());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            try {
                variant.setStatus(VariantStatus.valueOf(request.getStatus()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (request.getVariantImageUrl() != null) {
            variant.setImageUrl(request.getVariantImageUrl());
        }
        if (request.getVariantGalleryImageUrls() != null) {
            variant.setGalleryImageUrls(request.getVariantGalleryImageUrls());
        }
    }

    private StoreInventoryListingResponse buildInventoryResponse(ProductVariant variant) {
        Product product = productRepository.findById(variant.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", variant.getProductId().toString()));
        return StoreInventoryListingResponse.builder()
                .variantId(variant.getId())
                .productId(product.getId())
                .productTitle(product.getTitle())
                .productDescription(product.getDescription())
                .productImageUrl(product.getPrimaryImageUrl())
                .productGalleryImageUrls(product.getGalleryImageUrls())
                .storeId(variant.getStoreId())
                .sku(variant.getSku())
                .wholesalePriceCents(variant.getWholesalePriceCents())
                .retailPriceCents(variant.getRetailPriceCents())
                .stockQuantity(variant.getStockQuantity())
                .status(variant.getStatus() != null ? variant.getStatus().name() : null)
                .variantImageUrl(variant.getImageUrl())
                .variantGalleryImageUrls(variant.getGalleryImageUrls())
                .variantAttributes(variant.getVariantAttributes())
                .createdAt(variant.getCreatedAt())
                .build();
    }

    // ---------- transaction helpers ----------

    private List<StoreTransactionResponse> buildStoreTransactionResponses(UUID storeId) {
        List<Transaction> transactions = transactionRepository.findAllInvolvingStore(storeId);
        List<StoreTransactionResponse> result = new ArrayList<>();
        for (Transaction tx : transactions) {
            int itemsCount = transactionItemRepository.findByTransactionId(tx.getId()).size();
            String role;
            if (storeId.equals(tx.getOriginatingStoreId()) && storeId.equals(tx.getFulfillingStoreId())) {
                role = "BOTH";
            } else if (storeId.equals(tx.getOriginatingStoreId())) {
                role = "HOST";
            } else {
                role = "FULFILL";
            }
            result.add(StoreTransactionResponse.builder()
                    .id(tx.getId())
                    .status(tx.getStatus() != null ? tx.getStatus().name() : null)
                    .originatingStoreId(tx.getOriginatingStoreId())
                    .fulfillingStoreId(tx.getFulfillingStoreId())
                    .totalAmountCents(tx.getTotalRetailCents())
                    .createdAt(tx.getCreatedAt())
                    .itemsCount(itemsCount)
                    .role(role)
                    .build());
        }
        return result;
    }
}
