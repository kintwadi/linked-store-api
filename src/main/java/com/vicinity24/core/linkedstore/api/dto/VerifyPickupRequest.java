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
public class VerifyPickupRequest {

    private String secureToken;

    private String fallbackCode;

    @NotNull(message = "Scanning store user ID is required")
    private UUID scanningUserId;

    private String deviceId;
}
