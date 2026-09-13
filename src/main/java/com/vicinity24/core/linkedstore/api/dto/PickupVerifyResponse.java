package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PickupVerifyResponse {

    private String status;
    private UUID transactionId;
    private String transactionStatus;
    private Integer arbitrageMarginCents;
    private String currency;
    private UUID marginToStoreId;
    private String marginToStoreConnectId;
    private String stripeTransferId;
    private String qrScannedAt;
    private String message;
}
