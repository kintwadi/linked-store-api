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
public class TransactionResponse {

    private UUID id;
    private String status;
    private UUID originatingStoreId;
    private String originatingStoreName;
    private UUID fulfillingStoreId;
    private String fulfillingStoreName;
    private String stripePaymentIntentId;
    private Integer totalRetailCents;
    private Integer wholesalePayoutCents;
    private Integer arbitrageMarginCents;
    private String currency;
    private UUID productId;
    private String productTitle;
    private String productImageUrl;
    private UUID variantId;
    private String sku;
    private String variantAttributesJson;
    private String qrSecureToken;
    private String qrFallbackCode;
    private String createdAt;
    private String updatedAt;
    private UUID runnerId;
    private Integer perspectivePriceCents;
    private String perspectiveRole;
    private UUID perspectiveStoreId;
}
