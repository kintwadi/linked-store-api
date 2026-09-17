package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStoreInviteRequest {

    @NotBlank(message = "role is required")
    private String role;

    private String prefillEmail;

    private Integer ttlDays;
}
