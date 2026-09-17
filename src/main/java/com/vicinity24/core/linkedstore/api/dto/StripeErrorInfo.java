package com.vicinity24.core.linkedstore.api.dto;

import com.stripe.exception.StripeException;

/**
 * Null-safe extraction of structured fields from {@link StripeException}.
 * In stripe-java 24+ the structured fields (code, declineCode, user-facing message)
 * are carried on the inner {@link com.stripe.model.StripeError} returned by
 * {@link StripeException#getStripeError()}, not directly on the exception itself.
 * Direct getters like {@code getDeclineCode()} either do not exist or always
 * return {@code null} on newer versions, so always go through the inner error.
 */
public record StripeErrorInfo(String userMessage, String code, String declineCode) {

    public static StripeErrorInfo from(StripeException sxp) {
        String um = null;
        String co = null;
        String dc = null;
        if (sxp != null) {
            com.stripe.model.StripeError se = sxp.getStripeError();
            if (se != null) {
                um = se.getMessage();
                co = se.getCode();
                dc = se.getDeclineCode();
            }
            if (um == null || um.isBlank()) um = sxp.getMessage();
        }
        return new StripeErrorInfo(um, co, dc);
    }
}
