package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequest {

    @NotBlank(message = "productId is required")
    private String productId;

    private String variantId;

    @NotNull(message = "originatingStoreId is required")
    private String originatingStoreId;

    private UUID variantAttributesJson;

    @Builder.Default
    private Integer radiusKm = 5;

    @Builder.Default
    private Integer countdownSeconds = 900;
}
