package com.vicinity24.core.linkedstore.api.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutResponse {

    private String provider;
    private String payoutId;
    private long amountCents;
    private String currency;
    private String status;
    private String destinationAccountId;
}
