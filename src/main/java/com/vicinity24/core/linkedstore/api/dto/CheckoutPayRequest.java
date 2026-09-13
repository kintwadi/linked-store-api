package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutPayRequest {

    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;

    @NotBlank(message = "Stripe payment method ID is required")
    private String paymentMethodId;

    private String idempotencyKey;

    private String customerEmail;

    private String customerName;

    private String paymentProvider;
}
