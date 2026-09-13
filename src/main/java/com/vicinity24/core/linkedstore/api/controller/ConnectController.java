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
            if (stripeConnectId == null || stripeConnectId.isBlank() || stripeConnectId.startsWith("acct_connected_")) {
                final AccountCreateParams.Builder b = AccountCreateParams.builder()
                        .setType(AccountCreateParams.Type.EXPRESS)
                        .setCountry("US")
                        .setEmail(request.getOwnerEmail() != null ? request.getOwnerEmail() : "store-" + store.getId() + "@vicinity24.dev")
                        .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                        .putMetadata("storeId", store.getId().toString())
                        .putMetadata("businessName", store.getBusinessName())
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
                store.setStripeConnectId(stripeConnectId);
                storeRepository.save(store);
                isNewAccount = true;
                log.info("Connect: created new Stripe Account {} for store {} ({})",
                        stripeConnectId, store.getId(), store.getBusinessName());
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

            final AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
                    .setAccount(stripeConnectId)
                    .setRefreshUrl(request.getRefreshUrl())
                    .setReturnUrl(request.getReturnUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setCollect(AccountLinkCreateParams.Collect.CURRENTLY_DUE)
                    .build();

            try {
                final com.stripe.model.AccountLink link = com.stripe.model.AccountLink.create(linkParams);
                return ResponseEntity.status(HttpStatus.CREATED).body(ConnectOnboardingResponse.builder()
                        .url(link.getUrl())
                        .object("account_link")
                        .status(isNewAccount ? "new_account" : "existing_account")
                        .stripeConnectId(stripeConnectId)
                        .storeId(store.getId())
                        .build());
            } catch (StripeException ex) {
                String fallbackUrl = "https://dashboard.stripe.com/" + stripeConnectId;
                log.warn("Connect: AccountLink.create failed for {}, falling back to dashboard URL: {}", stripeConnectId, ex.getMessage());
                return ResponseEntity.status(HttpStatus.CREATED).body(ConnectOnboardingResponse.builder()
                        .url(fallbackUrl)
                        .object("account_link")
                        .status(isNewAccount ? "new_account" : "existing_account")
                        .stripeConnectId(stripeConnectId)
                        .storeId(store.getId())
                        .message("Could not generate Express onboarding link (dashboard access provided): " + ex.getMessage())
                        .build());
            }
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
            final String dashboardUrl = "https://dashboard.stripe.com/" + store.getStripeConnectId();
            return ResponseEntity.ok(ConnectOnboardingResponse.builder()
                    .url(dashboardUrl)
                    .object("login_link")
                    .status("ok")
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

    public record ConnectLoginLinkRequest(@NotNull UUID storeId) {}
}
