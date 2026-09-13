package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreSummaryResponse {

    private UUID id;
    private String businessName;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String stripeConnectId;
    private Boolean onboarded;
    private Boolean chargesEnabled;
    private Boolean payoutsEnabled;
    private String subscriptionStatus;
    private String logoUrl;
}
