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
public class ReservationResponse {

    private Boolean accepted;
    private UUID transactionId;
    private UUID variantId;
    private String productTitle;
    private String productImageUrl;
    private String sku;
    private Integer countdownSeconds;
    private OffsetDateTime expiresAt;
    private Integer totalRetailCents;
    private Integer wholesalePayoutCents;
    private Integer arbitrageMarginCents;
    private String currency;
    private UUID originatingStoreId;
    private UUID fulfillingStoreId;
    private String qrSecureToken;
    private String qrFallbackCode;
    private String qrTokenId;
    private String qrExpiresAt;
    private UUID runnerId;
    private String status;
    private String message;
}
