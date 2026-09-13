package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
public class CreateUserRequest {

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    @NotBlank(message = "name is required")
    private String name;

    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    private String phone;

    private String role;

    private UUID storeId;

    private Boolean isGlobalAdmin;
}
