package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStoreRequest {

    @NotBlank(message = "businessName is required")
    private String businessName;

    @NotNull(message = "latitude is required")
    private BigDecimal latitude;

    @NotNull(message = "longitude is required")
    private BigDecimal longitude;

    private String countryCode;
    private String currencyCode;

    private String logoUrl;

    private String heroImageUrl;

    private String address;

    private String postalCode;

    private String stripeConnectId;
    private String subscriptionStatus;

    public String getStripeConnectId() {
        if (stripeConnectId == null || stripeConnectId.isBlank()) {
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[12];
            random.nextBytes(bytes);
            return "acct_connected_new_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase();
        }
        return stripeConnectId;
    }
}
