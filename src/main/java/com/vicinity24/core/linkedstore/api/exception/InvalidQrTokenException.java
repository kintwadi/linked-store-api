package com.vicinity24.core.linkedstore.api.exception;

import lombok.Getter;

@Getter
public class InvalidQrTokenException extends RuntimeException {

    private final String secureToken;
    private final RejectReason rejectReason;

    public enum RejectReason {
        TOKEN_NOT_FOUND,
        TOKEN_EXPIRED,
        TOKEN_ALREADY_SCANNED,
        INVALID_SIGNATURE,
        UNAUTHORIZED_SCANNER,
        TRANSACTION_NOT_IN_PAID_STATE
    }

    public InvalidQrTokenException(String message, String secureToken, RejectReason rejectReason) {
        super(message);
        this.secureToken = secureToken;
        this.rejectReason = rejectReason;
    }
}
