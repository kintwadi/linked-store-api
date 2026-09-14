package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreInventoryListingRequest {

    private UUID productId;

    private String title;

    private String description;

    private String imageUrl;

    private String sku;

    private Integer wholesalePriceCents;

    private Integer retailPriceCents;

    private Integer stockQuantity;

    private String status;

    private String variantImageUrl;

    private List<String> productGalleryImageUrls;

    private List<String> variantGalleryImageUrls;

    private Map<String, Object> variantAttributes;
}
