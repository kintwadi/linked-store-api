package com.vicinity24.core.linkedstore.api.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class InventoryLockFailedException extends RuntimeException {

    private final UUID variantId;
    private final Integer requestedQuantity;
    private final Integer availableQuantity;

    public InventoryLockFailedException(String message, UUID variantId, Integer requestedQuantity, Integer availableQuantity) {
        super(message);
        this.variantId = variantId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public InventoryLockFailedException(String message) {
        super(message);
        this.variantId = null;
        this.requestedQuantity = null;
        this.availableQuantity = null;
    }
}
