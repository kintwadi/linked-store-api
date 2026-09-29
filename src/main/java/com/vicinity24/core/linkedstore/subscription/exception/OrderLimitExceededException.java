package com.vicinity24.core.linkedstore.subscription.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@Getter
@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class OrderLimitExceededException extends RuntimeException {

    private final UUID storeId;
    private final int currentCount;
    private final int limit;
    private final String errorCode = "ORDER_LIMIT_EXCEEDED";

    public OrderLimitExceededException(UUID storeId, int currentCount, int limit) {
        super(String.format(
                "Store %s has exceeded its monthly order limit of %d (current count: %d). Upgrade plan to continue.",
                storeId, limit, currentCount));
        this.storeId = storeId;
        this.currentCount = currentCount;
        this.limit = limit;
    }
}
