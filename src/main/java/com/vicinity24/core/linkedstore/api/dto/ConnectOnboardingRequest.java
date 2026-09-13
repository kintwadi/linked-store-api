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
public class ConnectOnboardingRequest {

    @NotNull(message = "storeId is required")
    private UUID storeId;

    @NotBlank(message = "refreshUrl is required")
    private String refreshUrl;

    @NotBlank(message = "returnUrl is required")
    private String returnUrl;

    private String ownerEmail;
    private String ownerFullName;
    private String businessType;
}
