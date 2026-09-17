package com.vicinity24.core.linkedstore.api.dto;

/**
 * Final output of the full Stripe link strategy chain
 * ({@link com.vicinity24.core.linkedstore.api.controller.ConnectController#resolveOnboardingOrDashboardLink}).
 *
 * If url == null, lastError contains the most useful user-facing failure observed during
 * the strategy walk so 503 responses can show STRIPE'S actual reason (code + message)
 * instead of a vague local guess. strategiesTried is a |-joined log string useful for
 * debugging: e.g. "account_onboarding_currently_due|login_link".
 */
public record ResolvedLink(String url, String object, String strategiesTried, StripeErrorInfo lastError) {

    public static ResolvedLink empty(String lastStrategy, StripeErrorInfo lastError) {
        return new ResolvedLink(null, null, lastStrategy, lastError);
    }
}
