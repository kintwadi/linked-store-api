package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPurchaseRequest {

    private String planCode;
    private String provider;
    private String paymentMethodId;
    private String customerEmail;
    private String idempotencyKey;
}
