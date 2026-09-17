package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterStoreRequest {

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    @NotBlank(message = "password is required")
    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    @NotBlank(message = "fullName is required")
    private String fullName;

    private String phone;

    private String businessName;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Pattern(regexp = "^$|^[A-Z]{2}$", message = "countryCode must be a 2-letter ISO alpha-2 code (e.g. US)")
    private String countryCode;

    @Pattern(regexp = "^$|^[A-Z]{3}$", message = "currencyCode must be a 3-letter ISO 4217 code (e.g. USD)")
    private String currencyCode;

    private String logoUrl;

    private String heroImageUrl;

    private String address;

    private String postalCode;

    private Boolean isStoreAdmin;

    private String inviteToken;
}
