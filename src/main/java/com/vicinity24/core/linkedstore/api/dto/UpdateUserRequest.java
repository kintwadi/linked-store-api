package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    private String name;
    private String phone;
    private String role;
    private String status;

    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    private Boolean isGlobalAdmin;
    private UUID storeId;
}
