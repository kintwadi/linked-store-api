package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreInventoryListingResponse {

    private UUID variantId;

    private UUID productId;

    private String productTitle;

    private String productDescription;

    private String productImageUrl;

    private UUID storeId;

    private String sku;

    private Integer wholesalePriceCents;

    private Integer retailPriceCents;

    private Integer stockQuantity;

    private String status;

    private String variantImageUrl;

    private List<String> productGalleryImageUrls;

    private List<String> variantGalleryImageUrls;

    private Map<String, Object> variantAttributes;

    private OffsetDateTime createdAt;
}
