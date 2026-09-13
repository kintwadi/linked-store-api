package com.vicinity24.core.linkedstore.api.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class InsufficientStockException extends RuntimeException {

    private final UUID variantId;
    private final Integer requested;
    private final Integer available;

    public InsufficientStockException(String message, UUID variantId, Integer requested, Integer available) {
        super(message);
        this.variantId = variantId;
        this.requested = requested;
        this.available = available;
    }
}
