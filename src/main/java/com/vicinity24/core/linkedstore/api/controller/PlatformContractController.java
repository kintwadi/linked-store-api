package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.AccountUpdateParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.ConnectOnboardingRequest;
import com.vicinity24.core.linkedstore.api.dto.ResolvedLink;
import com.vicinity24.core.linkedstore.api.dto.StripeErrorInfo;
import com.vicinity24.core.linkedstore.api.entity.PlatformContract;
import com.vicinity24.core.linkedstore.api.entity.PlatformContractMode;
import com.vicinity24.core.linkedstore.api.service.PlatformContractService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/platform-contract")
@RequiredArgsConstructor
@CrossOrigin(value = "*", maxAge = 3600)
public class PlatformContractController {

    private final PlatformContractService contractService;
    private final StripeConfig stripeConfig;

    @GetMapping("/public")
    public ResponseEntity<Map<String, Object>> getPublicContract() {
        PlatformContract c = contractService.getEffectiveContract();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", c.getMode().name());
        body.put("feePercentage", c.getFeePercentage());
        body.put("isConfigured", contractService.isConfigured());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/admin")
    public ResponseEntity<PlatformContractAdminResponse> getAdminContract() {
        PlatformContract c = contractService.getEffectiveContract();
        return ResponseEntity.ok(toAdminResponse(c));
    }

    @PutMapping("/admin")
    public ResponseEntity<PlatformContractAdminResponse> updateAdminContract(
            @RequestBody PlatformContractUpdateRequest request) {
        PlatformContractMode mode;
        try {
            mode = PlatformContractMode.valueOf(request.getMode());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
        if (mode == PlatformContractMode.FEE_BASED
                && (request.getFeePercentage() == null
                    || request.getFeePercentage() < 0
                    || request.getFeePercentage() > 100)) {
            return ResponseEntity.badRequest().build();
        }
        PlatformContract updated = contractService.updateContract(
                mode, request.getFeePercentage(), request.getPlatformPayoutConnectId());
        return ResponseEntity.ok(toAdminResponse(updated));
    }

    @PostMapping("/admin/platform-onboarding")
    public ResponseEntity<Map<String, Object>> createPlatformOnboardingLink(
            @RequestBody PlatformOnboardingRequest request) {
        ensureStripeKey();
        PlatformContract contract = contractService.getEffectiveContract();
        String stripeConnectId = contract.getPlatformPayoutConnectId();
        boolean isNewAccount = false;

        try {
            ConnectOnboardingRequest resolver = ConnectOnboardingRequest.builder()
                    .country(request != null ? request.getCountry() : null)
                    .defaultCurrency(request != null ? request.getDefaultCurrency() : null)
                    .build();
            String country = resolver.resolvedCountry();
            String currency = resolver.resolvedDefaultCurrency();

            boolean needsNewAccount = stripeConnectId == null || stripeConnectId.isBlank()
                    || stripeConnectId.startsWith("acct_connected_");

            if (!needsNewAccount) {
                try {
                    Account existing = Account.retrieve(stripeConnectId);
                    String existingCountry = existing.getCountry();
                    if (existingCountry == null || !existingCountry.equalsIgnoreCase(country)) {
                        needsNewAccount = true;
                        log.info("Platform contract: existing account {} country {} differs from selected {}; creating new account",
                                stripeConnectId, existingCountry, country);
                    }
                } catch (StripeException ex) {
                    needsNewAccount = true;
                    log.warn("Platform contract: could not retrieve existing account {}; creating new one", stripeConnectId);
                }
            }

            if (needsNewAccount) {
                AccountCreateParams.Builder b = AccountCreateParams.builder()
                        .setType(AccountCreateParams.Type.EXPRESS)
                        .setCountry(country)
                        .setDefaultCurrency(currency.toLowerCase(Locale.ROOT))
                        .setEmail("platform@vicinity24.dev")
                        .setBusinessType(AccountCreateParams.BusinessType.COMPANY)
                        .putMetadata("purpose", "platform_payout_account")
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
                Account account = Account.create(b.build());
                stripeConnectId = account.getId();
                contractService.updateContract(
                        contract.getMode(),
                        contract.getFeePercentage(),
                        stripeConnectId);
                isNewAccount = true;
                log.info("Platform contract: created new platform Stripe Connect account {} (country={}, currency={})",
                        stripeConnectId, country, currency);
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
                }
            } catch (StripeException capEx) {
                log.warn("Platform contract: could not ensure capabilities: {}", capEx.getMessage());
            }

            String refreshUrl = "https://dinretail.com/admin";
            String returnUrl = "https://dinretail.com/admin";
            ResolvedLink resolved = ConnectController.resolveOnboardingOrDashboardLink(
                    stripeConnectId, isNewAccount, refreshUrl, returnUrl);

            Map<String, Object> body = new LinkedHashMap<>();
            if (resolved.url() == null) {
                body.put("status", "error");
                body.put("message", "Stripe could not create an onboarding session.");
                body.put("stripeConnectId", stripeConnectId);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
            }
            body.put("url", resolved.url());
            body.put("status", isNewAccount ? "new_account" : "existing_account");
            body.put("stripeConnectId", stripeConnectId);
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (StripeException ex) {
            StripeErrorInfo sei = StripeErrorInfo.from(ex);
            log.error("Platform contract: failed to create platform onboarding link", ex);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", sei.userMessage() != null ? sei.userMessage() : ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    @DeleteMapping("/admin/platform-account")
    public ResponseEntity<Map<String, Object>> disconnectPlatformAccount() {
        ensureStripeKey();
        PlatformContract contract = contractService.getEffectiveContract();
        String stripeConnectId = contract.getPlatformPayoutConnectId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previousStripeConnectId", stripeConnectId);

        if (stripeConnectId == null || stripeConnectId.isBlank()) {
            body.put("status", "ok");
            body.put("stripeDeleted", false);
            body.put("message", "No platform Stripe account was linked.");
            return ResponseEntity.ok(body);
        }

        boolean stripeDeleted = false;
        String message = "Platform Stripe account disconnected.";
        try {
            Account account = Account.retrieve(stripeConnectId);
            account.delete();
            stripeDeleted = true;
            message = "Platform Stripe account removed from Stripe and disconnected.";
            log.info("Platform contract: deleted platform Stripe Connect account {}", stripeConnectId);
        } catch (StripeException ex) {
            StripeErrorInfo sei = StripeErrorInfo.from(ex);
            String stripeMessage = sei.userMessage() != null ? sei.userMessage() : ex.getMessage();
            message = "Platform Stripe account was unlinked locally, but Stripe did not remove it. "
                    + "You can still create a new account next time. Details: " + stripeMessage;
            log.warn("Platform contract: unlinking {} after Stripe delete failed: {}", stripeConnectId, stripeMessage);
        }

        contractService.updateContract(contract.getMode(), contract.getFeePercentage(), null);
        body.put("status", stripeDeleted ? "deleted" : "unlinked_with_warning");
        body.put("stripeDeleted", stripeDeleted);
        body.put("message", message);
        return ResponseEntity.ok(body);
    }

    private void ensureStripeKey() {
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }
    }

    private PlatformContractAdminResponse toAdminResponse(PlatformContract c) {
        return PlatformContractAdminResponse.builder()
                .mode(c.getMode().name())
                .feePercentage(c.getFeePercentage())
                .isConfigured(contractService.isConfigured())
                .platformPayoutConnectId(c.getPlatformPayoutConnectId())
                .updatedAt(c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformContractAdminResponse {
        private String mode;
        private Double feePercentage;
        private Boolean isConfigured;
        private String platformPayoutConnectId;
        private String updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformContractUpdateRequest {
        private String mode;
        private Double feePercentage;
        private String platformPayoutConnectId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformOnboardingRequest {
        private String country;
        private String defaultCurrency;
    }
}
