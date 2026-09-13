package com.vicinity24.core.linkedstore.api.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutRequest {

    private String provider;
    private long amountCents;
    private String currency;
    private String destinationAccountId;
    private String transferGroup;
    private String route;
}
