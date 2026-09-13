package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckAvailabilityRequest {

    @NotNull(message = "Originating store ID is required")
    private UUID originatingStoreId;

    @NotNull(message = "Product ID is required")
    private UUID productId;

    @NotNull(message = "Variant ID is required")
    private UUID variantId;

    @Positive(message = "Quantity must be positive")
    @Builder.Default
    private Integer quantity = 1;

    private Map<String, Object> variantAttributesFilter;

    private BigDecimal customerLatitude;

    private BigDecimal customerLongitude;
}
