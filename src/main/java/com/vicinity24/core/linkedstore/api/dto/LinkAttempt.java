package com.vicinity24.core.linkedstore.api.dto;

/**
 * Result from a single attempt to create a Stripe link (AccountLink / LoginLink).
 * On success url is set; on failure error carries the structured StripeErrorInfo
 * (so callers can surface the Stripe-provided message/code instead of generic text).
 * strategyName is a short snake_case tag used for logging + strategy-chain diagnostics
 * (e.g. "account_onboarding_currently_due", "login_link").
 */
public record LinkAttempt(String url, String strategyName, StripeErrorInfo error) {}
