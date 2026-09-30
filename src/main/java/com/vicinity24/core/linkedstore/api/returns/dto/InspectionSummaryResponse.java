package com.vicinity24.core.linkedstore.api.returns.dto;

import java.util.UUID;

public record InspectionSummaryResponse(
        UUID recordId,
        UUID refundId,
        UUID transactionId,
        UUID variantId,
        UUID productId,
        String productTitle,
        String sku,
        String productImageUrl,
        UUID fulfillingStoreId,
        String storeNameFulfilling,
        UUID originatingStoreId,
        String storeNameOriginating,
        Integer quantity,
        String status,
        String notes,
        String createdAt,
        String inspectedAt,
        String inspectedByUserId,
        String inspectedByStoreId,
        Boolean canAct
) {}

