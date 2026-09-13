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
public class VerifyPickupResponse {

    private UUID transactionId;
    private String transactionStatus;
    private UUID qrTokenId;
    private OffsetDateTime scannedAt;
    private OffsetDateTime settledAt;
    private UUID fulfillingStoreId;
    private UUID originatingStoreId;
    private Integer wholesalePayoutCents;
    private Integer arbitrageMarginCents;
    private String fulfillmentNote;
}
