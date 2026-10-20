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
    /**
     * Optional Stripe charge (ch_...) to draw this transfer from. When the funds are still
     * pending (separate charges and transfers), the transfer must reference the originating
     * charge via source_transaction, otherwise Stripe debits the platform's available balance.
     */
    private String sourceTransaction;
}
