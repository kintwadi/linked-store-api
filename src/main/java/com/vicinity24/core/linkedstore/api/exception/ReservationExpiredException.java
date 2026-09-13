package com.vicinity24.core.linkedstore.api.exception;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
public class ReservationExpiredException extends RuntimeException {

    private final UUID transactionId;
    private final UUID inventoryLockId;
    private final OffsetDateTime expiredAt;

    public ReservationExpiredException(String message, UUID transactionId, UUID inventoryLockId, OffsetDateTime expiredAt) {
        super(message);
        this.transactionId = transactionId;
        this.inventoryLockId = inventoryLockId;
        this.expiredAt = expiredAt;
    }

    public ReservationExpiredException(String message) {
        super(message);
        this.transactionId = null;
        this.inventoryLockId = null;
        this.expiredAt = null;
    }
}
