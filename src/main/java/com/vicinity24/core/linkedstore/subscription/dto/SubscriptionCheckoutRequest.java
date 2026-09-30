package com.vicinity24.core.linkedstore.subscription.dto;

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
public class SubscriptionCheckoutRequest {

    @NotNull
    private UUID storeId;

    @NotBlank
    private String planCode;

    @NotBlank
    @Builder.Default
    private String interval = "monthly";

    private String successUrl;

    private String cancelUrl;
}
