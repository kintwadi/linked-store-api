package com.vicinity24.core.linkedstore.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCheckoutResponse {

    private String type;

    private String url;

    private String sessionId;

    private String storeId;

    private String planCode;

    private String interval;

    private String message;

    private String contactSalesEmail;

    private String pricingPageUrl;
}
