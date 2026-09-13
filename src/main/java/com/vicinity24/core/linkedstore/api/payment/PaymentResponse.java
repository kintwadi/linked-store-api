package com.vicinity24.core.linkedstore.api.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private String provider;
    private String paymentIntentId;
    private String clientSecret;
    private long amountCents;
    private String currency;
    private String status;
    private String transferGroup;
    private OffsetDateTime createdAt;
    @Builder.Default
    private Map<String, Object> raw = new LinkedHashMap<>();
}
