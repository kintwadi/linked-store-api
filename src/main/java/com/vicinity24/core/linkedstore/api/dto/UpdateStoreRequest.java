package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStoreRequest {

    private String businessName;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String countryCode;
    private String currencyCode;
    private String logoUrl;
    private String heroImageUrl;
    private String address;
    private String postalCode;
    private String subscriptionStatus;
    private String stripeConnectId;
}
