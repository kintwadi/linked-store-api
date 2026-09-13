package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckAvailabilityResponse {

    private UUID transactionId;
    private UUID inventoryLockId;
    private UUID variantId;
    private Integer lockedQuantity;
    private Integer retailPriceCents;
    private Integer wholesalePriceCents;
    private OffsetDateTime expiresAt;
    private String status;
    private Integer lockMinutes;
}
