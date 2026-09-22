package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutSessionRequest {

    private UUID transactionId;

    private String productId;

    private String title;

    private String primaryImageUrl;

    @Positive(message = "amountCents must be positive")
    private Long amountCents;

    private String currency;

    private String variantId;

    @NotBlank(message = "successUrl is required")
    private String successUrl;

    @NotBlank(message = "cancelUrl is required")
    private String cancelUrl;

    private String customerEmail;

    private String originatingStoreId;
}
