package com.vicinity24.core.linkedstore.api.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    private String provider;
    private String paymentMethodId;
    private String customerEmail;
    private String idempotencyKey;
    private long amountCents;
    private String currency;
    private String description;
    private String transferGroup;
    private String customerId;
    @Builder.Default
    private boolean confirm = true;
    @Builder.Default
    private Map<String, String> metadata = new LinkedHashMap<>();
}
