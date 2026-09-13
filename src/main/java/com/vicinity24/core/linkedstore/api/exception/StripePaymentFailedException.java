package com.vicinity24.core.linkedstore.api.exception;

import lombok.Getter;

@Getter
public class StripePaymentFailedException extends RuntimeException {

    private final String stripeErrorCode;
    private final String declineCode;

    public StripePaymentFailedException(String message, String stripeErrorCode, String declineCode) {
        super(message);
        this.stripeErrorCode = stripeErrorCode;
        this.declineCode = declineCode;
    }

    public StripePaymentFailedException(String message, Throwable cause) {
        super(message, cause);
        this.stripeErrorCode = null;
        this.declineCode = null;
    }
}
