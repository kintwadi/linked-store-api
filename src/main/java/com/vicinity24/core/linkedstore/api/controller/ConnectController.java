package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.AccountUpdateParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.ConnectOnboardingRequest;
import com.vicinity24.core.linkedstore.api.dto.ConnectOnboardingResponse;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/connect")
@RequiredArgsConstructor
public class ConnectController {

    private final StripeConfig stripeConfig;
    private final StoreRepository storeRepository;
    private final TransactionRepository transactionRepository;

    @Value("${stripe.connect.webhook-secret:}")
    private String connectWebhookSecret;

    @PostMapping("/onboarding-link")
    public ResponseEntity<ConnectOnboardingResponse> createOnboardingLink(
            @Valid @RequestBody ConnectOnboardingRequest request) {

        ensureStripeKey();

        final Optional<Store> storeOpt = storeRepository.findById(request.getStoreId());
        if (storeOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message("Store not found.")
                    .storeId(request.getStoreId())
                    .build());
        }

        final Store store = storeOpt.get();

        try {
            String stripeConnectId = store.getStripeConnectId();
            boolean isNewAccount = false;
            String accountCountry = "US";
            if (stripeConnectId == null || stripeConnectId.isBlank() || stripeConnectId.startsWith("acct_connected_")) {
                final String country = request.resolvedCountry();
                final String currency = request.resolvedDefaultCurrency();
                final AccountCreateParams.BusinessType businessType = parseBusinessType(request.getBusinessType());
                final AccountCreateParams.Builder b = AccountCreateParams.builder()
                        .setType(AccountCreateParams.Type.EXPRESS)
                        .setCountry(country)
                        .setDefaultCurrency(currency)
                        .setEmail(request.getOwnerEmail() != null ? request.getOwnerEmail() : "store-" + store.getId() + "@vicinity24.dev")
                        .setBusinessType(businessType)
                        .putMetadata("storeId", store.getId().toString())
                        .putMetadata("businessName", store.getBusinessName())
                        .putMetadata("country", country)
                        .putMetadata("defaultCurrency", currency)
                        .setCapabilities(
                            AccountCreateParams.Capabilities.builder()
                                .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                    .setRequested(true)
                                    .build())
                                .setCardPayments(AccountCreateParams.Capabilities.CardPayments.builder()
                                    .setRequested(true)
                                    .build())
                                .build());

                final Account account = Account.create(b.build());
                stripeConnectId = account.getId();
                accountCountry = country;
                store.setStripeConnectId(stripeConnectId);
                storeRepository.save(store);
                isNewAccount = true;
                log.info("Connect: created new Stripe Account {} for store {} ({}), country={}, currency={}",
                        stripeConnectId, store.getId(), store.getBusinessName(), country, currency);
            } else {
                try {
                    Account existing = Account.retrieve(stripeConnectId);
                    if (existing.getCountry() != null) accountCountry = existing.getCountry();
                } catch (StripeException ignored) {}
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
                                .setRequested(true)
                                .build())
                            .setCardPayments(AccountUpdateParams.Capabilities.CardPayments.builder()
                                .setRequested(true)
                                .build())
                            .build())
                        .build();
                    current.update(upd);
                    log.info("Connect: ensured transfers+card_payments requested capability on {}", stripeConnectId);
                }
            } catch (StripeException capEx) {
                log.warn("Connect: could not ensure capabilities: {}", capEx.getMessage());
            }

            // --- Pick the right AccountLink strategy based on account state:
            //   * Brand-new Express accounts with nothing submitted yet → ACCOUNT_ONBOARDING (onboards fields we generate with
            //     CURRENTLY_DUE then fall back to ACCOUNT_UPDATE + EVENTUALLY_DUE then LoginLink
            //   * Already-submitted Express accounts → ACCOUNT_UPDATE lets Stripe allows edits; if that fails then
            //     create an Express Dashboard LoginLink (merchant-embeddable session) instead of platform-dashboard fallback)
            final String refreshUrl = request.getRefreshUrl();
            final String returnUrl = request.getReturnUrl();
            String url = tryCreateAccountLink(stripeConnectId, AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING,
                    AccountLinkCreateParams.Collect.CURRENTLY_DUE, refreshUrl, returnUrl);
            String strategy = "account_onboarding_currently_due";
            String linkObject = "account_link";

            if (url == null && !isNewAccount) {
                url = tryCreateAccountLink(stripeConnectId, AccountLinkCreateParams.Type.ACCOUNT_UPDATE,
                        AccountLinkCreateParams.Collect.EVENTUALLY_DUE, refreshUrl, returnUrl);
                strategy = "account_update_eventually_due";
            }
            if (url == null && !isNewAccount) {
                url = tryCreateAccountLink(stripeConnectId, AccountLinkCreateParams.Type.ACCOUNT_UPDATE,
                        AccountLinkCreateParams.Collect.CURRENTLY_DUE, refreshUrl, returnUrl);
                strategy = "account_update_currently_due";
            }
            if (url == null) {
                url = tryCreateExpressLoginLink(stripeConnectId);
                strategy = strategy + "|login_link_fallback";
                linkObject = "login_link";
            }
            if (url == null) {
                // Last-ditch fallback: platform-level Stripe dashboard URL (least preferred because platform-login page)
                url = "https://dashboard.stripe.com/express/" + stripeConnectId;
                strategy = strategy + "|express_dashboard_manual";
                linkObject = "express_dashboard";
                log.warn("Connect: no AccountLink + LoginLink exhausted; falling back to manual Express dashboard URL for {}",
                        stripeConnectId);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(ConnectOnboardingResponse.builder()
                    .url(url)
                    .object(linkObject)
                    .status(isNewAccount ? "new_account" : "existing_account")
                    .stripeConnectId(stripeConnectId)
                    .storeId(store.getId())
                    .message(strategy)
                    .build());
        } catch (StripeException ex) {
            log.error("Connect: failed onboarding-link for store {}", storeOpt.get().getId(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message(ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage())
                    .storeId(request.getStoreId())
                    .build());
        }
    }

    @PostMapping("/login-link")
    public ResponseEntity<ConnectOnboardingResponse> createLoginLink(
            @RequestBody @NotNull ConnectLoginLinkRequest request) {

        ensureStripeKey();
        final Optional<Store> storeOpt = storeRepository.findById(request.storeId());
        if (storeOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ConnectOnboardingResponse.builder()
                    .status("error").message("Store not found.").storeId(request.storeId()).build());
        }
        final Store store = storeOpt.get();
        if (store.getStripeConnectId() == null || store.getStripeConnectId().isBlank()
                || store.getStripeConnectId().startsWith("acct_connected_")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ConnectOnboardingResponse.builder()
                    .status("error").message("Store is not onboarded to Stripe yet. Create onboarding link first.")
                    .storeId(store.getId()).build());
        }
        try {
            final Account account = Account.retrieve(store.getStripeConnectId());
            String url = tryCreateExpressLoginLink(store.getStripeConnectId());
            String object = "login_link";
            String message = "express_login_link";
            if (url == null) {
                url = "https://dashboard.stripe.com/express/" + store.getStripeConnectId();
                object = "express_dashboard";
                message = "login_link_not_available|express_dashboard_manual";
                log.warn("Connect: login-link endpoint falling back to manual Express dashboard URL for {}",
                        store.getStripeConnectId());
            }
            return ResponseEntity.ok(ConnectOnboardingResponse.builder()
                    .url(url)
                    .object(object)
                    .status("ok")
                    .message(message)
                    .stripeConnectId(store.getStripeConnectId())
                    .storeId(store.getId())
                    .chargesEnabled(account.getChargesEnabled())
                    .payoutsEnabled(account.getPayoutsEnabled())
                    .build());
        } catch (StripeException ex) {
            log.error("Connect: failed login-link for store {}", store.getId(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message(ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage())
                    .storeId(store.getId())
                    .build());
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody String rawPayload,
            @RequestHeader("Stripe-Signature") String stripeSignature) {

        ensureStripeKey();
        final Event event;
        try {
            if (connectWebhookSecret == null || connectWebhookSecret.isBlank()) {
                log.warn("Connect: stripe.connect.webhook-secret is not set; skipping signature verification. " +
                        "Do NOT run this in production.");
                event = Webhook.constructEvent(rawPayload, stripeSignature, "whsec_placeholder");
            } else {
                event = Webhook.constructEvent(rawPayload, stripeSignature, connectWebhookSecret);
            }
        } catch (SignatureVerificationException ex) {
            log.warn("Connect: webhook signature failed verification: {}", ex.getMessage());
            final Map<String, Object> body = new HashMap<>();
            body.put("status", "signature_invalid");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        } catch (Exception ex) {
            log.error("Connect: webhook parse error", ex);
            final Map<String, Object> body = new HashMap<>();
            body.put("status", "parse_error");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        final String type = event.getType();
        switch (type) {
            case "account.updated":
                handleAccountUpdated(event);
                break;
            case "account.external_account.updated":
            case "account.external_account.created":
                handleAccountUpdated(event);
                break;
            case "checkout.session.completed":
                handleCheckoutSessionCompleted(event);
                break;
            case "checkout.session.async_payment_succeeded":
                handleCheckoutSessionCompleted(event);
                break;
            default:
                log.info("Connect: ignoring unhandled webhook event {}", type);
                break;
        }

        final Map<String, Object> body = new HashMap<>();
        body.put("status", "ok");
        body.put("handled_event", type);
        return ResponseEntity.ok(body);
    }

    private void handleAccountUpdated(Event event) {
        try {
            final Object dataObject = event.getDataObjectDeserializer().getObject().orElse(null);
            if (!(dataObject instanceof Account account)) return;
            final String storedStoreId = account.getMetadata() != null ? account.getMetadata().get("storeId") : null;
            if (storedStoreId == null) {
                final Optional<Store> byConnect = storeRepository.findByStripeConnectId(account.getId());
                if (byConnect.isPresent()) {
                    final Store s = byConnect.get();
                    log.info("Connect: account.updated storeId={} (matched by connectId {}): charges={} payouts={}",
                            s.getId(), account.getId(), account.getChargesEnabled(), account.getPayoutsEnabled());
                } else {
                    log.warn("Connect: account.updated event for account {} but cannot find storeId (no metadata/store mapping)", account.getId());
                }
                return;
            }
            final UUID uuid;
            try { uuid = UUID.fromString(storedStoreId); }
            catch (IllegalArgumentException iae) { log.warn("Connect: invalid storeId in account metadata {}", storedStoreId); return; }
            storeRepository.findById(uuid).ifPresent(store -> {
                boolean changed = false;
                if (!account.getId().equals(store.getStripeConnectId())) {
                    store.setStripeConnectId(account.getId());
                    changed = true;
                }
                if (changed) storeRepository.save(store);
                log.info("Connect: account.updated for store {} (connectId={}): chargesEnabled={} payoutsEnabled={}",
                        store.getId(), account.getId(), account.getChargesEnabled(), account.getPayoutsEnabled());
            });
        } catch (Exception ex) {
            log.error("Connect: handleAccountUpdated failed for event {}", event.getId(), ex);
        }
    }

    private void handleCheckoutSessionCompleted(Event event) {
        try {
            final Object dataObject = event.getDataObjectDeserializer().getObject().orElse(null);
            if (!(dataObject instanceof Session session)) return;
            final String txIdRaw = session.getMetadata() != null ? session.getMetadata().get("transactionId") : null;
            if (txIdRaw == null) {
                log.warn("Connect: checkout.session.completed missing transactionId metadata: session={}", session.getId());
                return;
            }
            final UUID txId;
            try { txId = UUID.fromString(txIdRaw); }
            catch (IllegalArgumentException iae) { log.warn("Connect: invalid transactionId metadata {}", txIdRaw); return; }
            transactionRepository.findById(txId).ifPresent(tx -> {
                if (tx.getStatus() != TransactionStatus.PAID && tx.getStatus() != TransactionStatus.PICKED_UP) {
                    tx.setStatus(TransactionStatus.PAID);
                }
                if (tx.getStripePaymentIntentId() == null || tx.getStripePaymentIntentId().isBlank()) {
                    tx.setStripePaymentIntentId(session.getPaymentIntent());
                }
                transactionRepository.save(tx);
                log.info("Connect: checkout.session.completed marked transaction {} PAID pi={}", tx.getId(), session.getPaymentIntent());
            });
        } catch (Exception ex) {
            log.error("Connect: handleCheckoutSessionCompleted failed for event {}", event.getId(), ex);
        }
    }

    private void ensureStripeKey() {
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }
    }

    static AccountCreateParams.BusinessType parseBusinessType(String raw) {
        if (raw == null || raw.isBlank()) return AccountCreateParams.BusinessType.INDIVIDUAL;
        return switch (raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "INDIVIDUAL", "SOLE_PROPRIETORSHIP", "SOLETRADER" -> AccountCreateParams.BusinessType.INDIVIDUAL;
            case "COMPANY", "CORPORATION", "LLC", "LIMITED_LIABILITY_COMPANY", "PRIVATE_LIMITED", "PLC" -> AccountCreateParams.BusinessType.COMPANY;
            case "NON_PROFIT", "NONPROFIT", "CHARITY" -> AccountCreateParams.BusinessType.NON_PROFIT;
            case "GOVERNMENT_ENTITY", "GOVERNMENT" -> AccountCreateParams.BusinessType.GOVERNMENT_ENTITY;
            default -> AccountCreateParams.BusinessType.INDIVIDUAL;
        };
    }

    static String resolveCountry(String country) {
        return ConnectOnboardingRequest.builder().country(country).build().resolvedCountry();
    }

    static String resolveCurrencyForCountry(String country, String explicitCurrency) {
        return ConnectOnboardingRequest.builder()
                .country(country)
                .defaultCurrency(explicitCurrency)
                .build()
                .resolvedDefaultCurrency();
    }

    static String tryCreateAccountLink(
            String stripeConnectId,
            AccountLinkCreateParams.Type linkType,
            AccountLinkCreateParams.Collect collect,
            String refreshUrl,
            String returnUrl) {
        try {
            AccountLinkCreateParams.Builder params = AccountLinkCreateParams.builder()
                    .setAccount(stripeConnectId)
                    .setType(linkType)
                    .setCollect(collect);
            if (refreshUrl != null && !refreshUrl.isBlank()) params.setRefreshUrl(refreshUrl);
            if (returnUrl  != null && !returnUrl.isBlank())  params.setReturnUrl(returnUrl);
            com.stripe.model.AccountLink link = com.stripe.model.AccountLink.create(params.build());
            log.info("Connect: AccountLink OK type={} collect={} account={}", linkType.name(), collect.name(), stripeConnectId);
            return link.getUrl();
        } catch (StripeException ex) {
            log.info("Connect: AccountLink skip type={} collect={} account={}: {} (code={})",
                    linkType.name(), collect.name(), stripeConnectId,
                    ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage(),
                    ex.getCode());
            return null;
        }
    }

    static String tryCreateExpressLoginLink(String stripeConnectId) {
        try {
            Map<String, Object> params = Map.of();
            // stripe-java uses account.loginLinks.create(params) via reflection-safe map-style API
            com.stripe.model.LoginLink created = com.stripe.model.LoginLink.createOnAccount(stripeConnectId, params);
            log.info("Connect: LoginLink OK account={}", stripeConnectId);
            return created.getUrl();
        } catch (StripeException | RuntimeException ex) {
            log.info("Connect: LoginLink skip account={}: {}", stripeConnectId, ex.getMessage());
            return null;
        }
    }

    public record ConnectLoginLinkRequest(@NotNull UUID storeId) {}
}
