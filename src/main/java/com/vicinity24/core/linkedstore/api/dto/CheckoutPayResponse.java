package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutPayResponse {

    private UUID transactionId;
    private String transactionStatus;
    private String stripePaymentIntentId;
    private String transferGroup;
    private Integer totalRetailCents;
    private Integer wholesalePayoutCents;
    private Integer arbitrageMarginCents;
    private Integer platformFeeCents;
    private String qrSecureToken;
    private String qrFallbackCode;
    private UUID qrTokenId;
    private String qrExpiresAt;
    private UUID runnerId;
}
