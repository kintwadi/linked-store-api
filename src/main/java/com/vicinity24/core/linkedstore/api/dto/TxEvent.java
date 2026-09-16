package com.vicinity24.core.linkedstore.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxEvent(
        String eventId,
        TxEventType type,
        OffsetDateTime createdAt,
        UUID transactionId,
        UUID storeId,
        UUID fulfillingStoreId,
        UUID originatingStoreId,
        UUID variantId,
        UUID productId,
        String productTitle,
        String productImageUrl,
        String sku,
        BigDecimal retailPrice,
        String currency,
        OffsetDateTime expiresAt,
        Integer countdownSeconds,
        String qrFallbackCode,
        String runnerId,
        String status,
        String message
) {
    public TxEvent withEventId(String eventId) {
        return new TxEvent(eventId, type(), createdAt(), transactionId(), storeId(),
                fulfillingStoreId(), originatingStoreId(), variantId(), productId(),
                productTitle(), productImageUrl(), sku(), retailPrice(), currency(),
                expiresAt(), countdownSeconds(), qrFallbackCode(), runnerId(), status(), message());
    }
}
