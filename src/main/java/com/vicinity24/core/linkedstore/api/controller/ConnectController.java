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
import com.stripe.param.LoginLinkCreateOnAccountParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.ConnectOnboardingRequest;
import com.vicinity24.core.linkedstore.api.dto.ConnectOnboardingResponse;
import com.vicinity24.core.linkedstore.api.dto.LinkAttempt;
import com.vicinity24.core.linkedstore.api.dto.ResolvedLink;
import com.vicinity24.core.linkedstore.api.dto.StripeErrorInfo;
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

import java.util.*;

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

    @GetMapping("/health")
    public Map<String, Object> connectHealth() {
        final Map<String, Object> out = new LinkedHashMap<>();
        final String key = com.stripe.Stripe.apiKey;
        if (key == null || key.isBlank()) {
            out.put("keyMode", "UNKNOWN");
            out.put("keyPrefixMasked", "(not set)");
            out.put("connectActivated", false);
            out.put("stripeErrorCode", "key_missing");
            out.put("stripeErrorMessage", "Stripe.apiKey is not configured — backend STRIPE_SECRET_KEY env var is empty.");
            return out;
        }
        final String mode;
        if (key.toLowerCase(Locale.ROOT).startsWith("sk_test_")) mode = "TEST";
        else if (key.toLowerCase(Locale.ROOT).startsWith("sk_live_")) mode = "LIVE";
        else if (key.toLowerCase(Locale.ROOT).startsWith("pk_")) mode = "PK_ONLY_INVALID";
        else mode = "UNKNOWN";
        final String prefix = key.length() <= 16 ? key : key.substring(0, 16) + "xxxxxxxx";
        out.put("keyMode", mode);
        out.put("keyPrefixMasked", prefix);
        try {
            ensureStripeKey();
            com.stripe.param.AccountListParams p = com.stripe.param.AccountListParams.builder().setLimit(1L).build();
            com.stripe.model.AccountCollection list = com.stripe.model.Account.list(p);
            out.put("connectActivated", true);
            out.put("connectedAccountsCount", list.getData() != null ? list.getData().size() : 0);
            out.put("stripeErrorCode", null);
            out.put("stripeErrorMessage", null);
        } catch (StripeException sx) {
            StripeErrorInfo sei = StripeErrorInfo.from(sx);
            out.put("connectActivated", false);
            out.put("stripeErrorCode", sei.code());
            out.put("stripeErrorMessage", sei.userMessage());
            if (sei.code() != null && sei.code().toLowerCase(Locale.ROOT).contains("platform_account_required")) {
                String activationUrl = "TEST".equalsIgnoreCase(mode)
                        ? "https://dashboard.stripe.com/test/settings/connect"
                        : "LIVE".equalsIgnoreCase(mode)
                            ? "https://dashboard.stripe.com/settings/connect"
                            : "https://dashboard.stripe.com/account/applications/settings";
                out.put("stripeActivationUrl", activationUrl);
                out.put("nextStep", "Open the URL above in Stripe dashboard (mode: " + mode + "), click 'Get started with Connect' → 'Platform or marketplace' → 'Managed / Express payouts'. Then restart backend.");
            }
        }
        return out;
    }

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

            final String refreshUrl = request.getRefreshUrl();
            final String returnUrl = request.getReturnUrl();
            ResolvedLink resolved = resolveOnboardingOrDashboardLink(
                    stripeConnectId, isNewAccount, refreshUrl, returnUrl);

            if (resolved.url() == null) {
                String message = "Stripe could not create an onboarding session. Try again in a moment, or "
                        + "ensure the Connect account is not restricted.";
                if (resolved.lastError() != null) {
                    StringBuilder sb = new StringBuilder();
                    if (resolved.lastError().userMessage() != null
                            && !resolved.lastError().userMessage().isBlank()) {
                        sb.append(resolved.lastError().userMessage());
                    }
                    if (resolved.lastError().code() != null && !resolved.lastError().code().isBlank()) {
                        if (!sb.isEmpty()) sb.append(" ");
                        sb.append("(Stripe code: ").append(resolved.lastError().code()).append(")");
                    }
                    if (resolved.lastError().declineCode() != null && !resolved.lastError().declineCode().isBlank()) {
                        if (!sb.isEmpty()) sb.append(" ");
                        sb.append("(decline: ").append(resolved.lastError().declineCode()).append(")");
                    }
                    if (!sb.isEmpty()) message = sb.toString();
                }
                log.warn("Connect: onboarding-link exhausted for account={} (store={}) strategies={}",
                        stripeConnectId, store.getId(), resolved.strategiesTried());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                        .status("error")
                        .message(message)
                        .storeId(store.getId())
                        .stripeConnectId(stripeConnectId)
                        .build());
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(ConnectOnboardingResponse.builder()
                    .url(resolved.url())
                    .object(resolved.object())
                    .status(isNewAccount ? "new_account" : "existing_account")
                    .stripeConnectId(stripeConnectId)
                    .storeId(store.getId())
                    .message(resolved.strategiesTried())
                    .build());
        } catch (StripeException ex) {
            StripeErrorInfo sei = StripeErrorInfo.from(ex);
            log.error("Connect: failed onboarding-link for store {}", storeOpt.get().getId(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message(sei.userMessage() != null ? sei.userMessage() : ex.getMessage())
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
            LinkAttempt loginAttempt = tryCreateExpressLoginLink(store.getStripeConnectId());
            String url = loginAttempt.url();
            String object = "login_link";
            String message = "express_login_link";
            if (url == null) {
                // DO NOT craft a fake https://dashboard.stripe.com/express/{acct_xxx} URL — that path does not exist on Stripe's side
                // and navigating the user there just lands them on a 404.
                StringBuilder sb = new StringBuilder();
                sb.append("Stripe Express dashboard session could not be created. "
                        + "This usually means onboarding details have not been fully submitted yet. "
                        + "Click 'Connect Stripe' once to finish onboarding first, then retry opening the dashboard.");
                if (loginAttempt.error() != null) {
                    if (loginAttempt.error().userMessage() != null && !loginAttempt.error().userMessage().isBlank()) {
                        sb.setLength(0);
                        sb.append(loginAttempt.error().userMessage());
                    }
                    if (loginAttempt.error().code() != null && !loginAttempt.error().code().isBlank()) {
                        sb.append(" (Stripe code: ").append(loginAttempt.error().code()).append(")");
                    }
                    if (loginAttempt.error().declineCode() != null && !loginAttempt.error().declineCode().isBlank()) {
                        sb.append(" (decline: ").append(loginAttempt.error().declineCode()).append(")");
                    }
                }
                log.warn("Connect: login-link endpoint could not generate LoginLink for store {}, account {}",
                        store.getId(), store.getStripeConnectId());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                        .status("error")
                        .message(sb.toString())
                        .storeId(store.getId())
                        .stripeConnectId(store.getStripeConnectId())
                        .chargesEnabled(account.getChargesEnabled())
                        .payoutsEnabled(account.getPayoutsEnabled())
                        .build());
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
            StripeErrorInfo sei = StripeErrorInfo.from(ex);
            log.error("Connect: failed login-link for store {}", store.getId(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ConnectOnboardingResponse.builder()
                    .status("error")
                    .message(sei.userMessage() != null ? sei.userMessage() : ex.getMessage())
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

    static LinkAttempt tryCreateAccountLink(
            String stripeConnectId,
            AccountLinkCreateParams.Type linkType,
            AccountLinkCreateParams.Collect collect,
            String refreshUrl,
            String returnUrl) {
        final String strategy = linkType.name().toLowerCase() + "_" + collect.name().toLowerCase();
        try {
            AccountLinkCreateParams.Builder params = AccountLinkCreateParams.builder()
                    .setAccount(stripeConnectId)
                    .setType(linkType)
                    .setCollect(collect);
            if (refreshUrl != null && !refreshUrl.isBlank()) params.setRefreshUrl(refreshUrl);
            if (returnUrl  != null && !returnUrl.isBlank())  params.setReturnUrl(returnUrl);
            com.stripe.model.AccountLink link = com.stripe.model.AccountLink.create(params.build());
            log.info("Connect: AccountLink OK strategy={} account={}", strategy, stripeConnectId);
            return new LinkAttempt(link.getUrl(), strategy, null);
        } catch (StripeException ex) {
            StripeErrorInfo sei = StripeErrorInfo.from(ex);
            log.info("Connect: AccountLink SKIP strategy={} account={}: {} (code={})",
                    strategy, stripeConnectId,
                    sei.userMessage() != null ? sei.userMessage() : ex.getMessage(),
                    sei.code());
            return new LinkAttempt(null, strategy, sei);
        }
    }

    static LinkAttempt tryCreateExpressLoginLink(String stripeConnectId) {
        try {
            try {
                Account current = Account.retrieve(stripeConnectId);
                boolean needUpdate = false;
                AccountUpdateParams.Builder upd = AccountUpdateParams.builder();
                if (current.getCapabilities() == null
                        || current.getCapabilities().getTransfers() == null
                        || current.getCapabilities().getCardPayments() == null) {
                    upd.setCapabilities(AccountUpdateParams.Capabilities.builder()
                            .setTransfers(AccountUpdateParams.Capabilities.Transfers.builder()
                                    .setRequested(true).build())
                            .setCardPayments(AccountUpdateParams.Capabilities.CardPayments.builder()
                                    .setRequested(true).build())
                            .build());
                    needUpdate = true;
                }
                if (needUpdate) {
                    current.update(upd.build());
                    log.info("Connect: LoginLink ensured transfers+card_payments requested on {}", stripeConnectId);
                }
            } catch (StripeException preEx) {
                StripeErrorInfo sei = StripeErrorInfo.from(preEx);
                log.warn("Connect: LoginLink pre-check failed for account {}: {} (code={})",
                        stripeConnectId,
                        sei.userMessage() != null ? sei.userMessage() : preEx.getMessage(),
                        sei.code());
            }

            LoginLinkCreateOnAccountParams params = LoginLinkCreateOnAccountParams.builder().build();
            com.stripe.model.LoginLink created = com.stripe.model.LoginLink.createOnAccount(stripeConnectId, params);
            log.info("Connect: LoginLink OK account={}", stripeConnectId);
            return new LinkAttempt(created.getUrl(), "login_link", null);
        } catch (StripeException | RuntimeException ex) {
            String msg = ex.getMessage();
            StripeErrorInfo sei = null;
            if (ex instanceof StripeException sxp) {
                sei = StripeErrorInfo.from(sxp);
                if (sei.userMessage() != null && !sei.userMessage().isBlank()) msg = sei.userMessage();
            }
            log.warn("Connect: LoginLink FAILED account={} code={} declineCode={}: {}",
                    stripeConnectId,
                    sei != null ? sei.code() : null,
                    sei != null ? sei.declineCode() : null,
                    msg, ex);
            return new LinkAttempt(null, "login_link", sei);
        }
    }

    /**
     * Centralized onboarding/dashboard URL resolver. Runs the strategy chain based on actual Stripe account
     * state (fetched live), and returns either a working URL or a ResolvedLink.lastError containing the most
     * useful StripeErrorInfo (user-facing message + code) we saw.
     *
     * Strategy ordering:
     *   1. BRAND-NEW accounts (isNewAccount OR details_submitted==false) → ACCOUNT_ONBOARDING + CURRENTLY_DUE.
     *      Stripe rejects ACCOUNT_ONBOARDING when details_submitted=true, so NEVER try it for re-onboard.
     *   2. ALREADY-SUBMITTED accounts (re-onboard click) → ACCOUNT_UPDATE, first EVENTUALLY_DUE then CURRENTLY_DUE.
     *   3. If steps 1-2 produced no URL (either account has 0 pending requirements, OR onboarding + all update
     *      variants rejected) → EXPRESS LOGIN LINK. This gives the merchant a live Express Dashboard session
     *      where they can edit business details, payouts, see transactions, etc. — this is the correct flow
     *      for "re-onboard" on an account that is fully onboarded and has nothing due.
     */
    static ResolvedLink resolveOnboardingOrDashboardLink(
            String stripeConnectId,
            boolean isNewAccount,
            String refreshUrl,
            String returnUrl) {
        Account account;
        try {
            account = Account.retrieve(stripeConnectId);
        } catch (StripeException ex) {
            StripeErrorInfo sei = StripeErrorInfo.from(ex);
            log.warn("Connect: resolveOnboarding: Account.retrieve({}) failed: {} (code={})",
                    stripeConnectId,
                    sei.userMessage() != null ? sei.userMessage() : ex.getMessage(),
                    sei.code());
            return ResolvedLink.empty("account_retrieve", sei);
        }

        Boolean detailsSubmitted = account.getDetailsSubmitted();
        boolean submitted = detailsSubmitted != null && detailsSubmitted;
        List<String> currentlyDue = account.getRequirements() != null && account.getRequirements().getCurrentlyDue() != null
                ? account.getRequirements().getCurrentlyDue() : List.of();
        List<String> eventuallyDue = account.getRequirements() != null && account.getRequirements().getEventuallyDue() != null
                ? account.getRequirements().getEventuallyDue() : List.of();
        boolean hasPending = !currentlyDue.isEmpty() || !eventuallyDue.isEmpty();

        log.info("Connect: resolveOnboarding account={} detailsSubmitted={} "
                        + "currentlyDue({})={}, eventuallyDue({})={}, isNewAccount={}",
                stripeConnectId, submitted,
                currentlyDue.size(), currentlyDue,
                eventuallyDue.size(), eventuallyDue,
                isNewAccount);

        StringBuilder strategies = new StringBuilder();
        StripeErrorInfo lastError = null;

        // Step 1: INITIAL ONBOARDING — only when details not yet submitted.
        if (!submitted) {
            LinkAttempt at = tryCreateAccountLink(
                    stripeConnectId,
                    AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING,
                    AccountLinkCreateParams.Collect.CURRENTLY_DUE,
                    refreshUrl, returnUrl);
            strategies.append(at.strategyName());
            if (at.url() != null) return new ResolvedLink(at.url(), "account_link", strategies.toString(), null);
            if (at.error() != null) lastError = at.error();
        } else {
            strategies.append("skip_account_onboarding(details_submitted=true)");
        }

        // Step 2: ACCOUNT_UPDATE variants (editing details/payouts on an already-submitted account).
        // Order: EVENTUALLY_DUE first (looser — works even if no CURRENTLY_DUE pending items), then CURRENTLY_DUE.
        // BUT: if the account has NO pending requirements at all, Stripe rejects BOTH with a 400. In that case
        // we skip straight to the LoginLink (step 3) without wasting attempts or polluting lastError.
        if (submitted && hasPending) {
            strategies.append("|");
            LinkAttempt at1 = tryCreateAccountLink(
                    stripeConnectId,
                    AccountLinkCreateParams.Type.ACCOUNT_UPDATE,
                    AccountLinkCreateParams.Collect.EVENTUALLY_DUE,
                    refreshUrl, returnUrl);
            strategies.append(at1.strategyName());
            if (at1.url() != null) return new ResolvedLink(at1.url(), "account_link", strategies.toString(), null);
            if (at1.error() != null) lastError = at1.error();

            strategies.append("|");
            LinkAttempt at2 = tryCreateAccountLink(
                    stripeConnectId,
                    AccountLinkCreateParams.Type.ACCOUNT_UPDATE,
                    AccountLinkCreateParams.Collect.CURRENTLY_DUE,
                    refreshUrl, returnUrl);
            strategies.append(at2.strategyName());
            if (at2.url() != null) return new ResolvedLink(at2.url(), "account_link", strategies.toString(), null);
            if (at2.error() != null) lastError = at2.error();
        } else if (submitted) {
            strategies.append("|skip_account_update(no_pending_reqs)");
        }

        // Step 3: EXPRESS LOGIN LINK — always works for a real, non-restricted Connect Express account.
        strategies.append("|");
        LinkAttempt lla = tryCreateExpressLoginLink(stripeConnectId);
        strategies.append(lla.strategyName());
        if (lla.url() != null) return new ResolvedLink(lla.url(), "login_link", strategies.toString(), null);
        if (lla.error() != null) lastError = lla.error();

        log.warn("Connect: resolveOnboarding exhausted strategies for account={} lastSeenCode={} lastMsg={}",
                stripeConnectId,
                lastError != null ? lastError.code() : null,
                lastError != null ? lastError.userMessage() : null);
        return ResolvedLink.empty(strategies.toString(), lastError);
    }

    public record ConnectLoginLinkRequest(@NotNull UUID storeId) {}
}
