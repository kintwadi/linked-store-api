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
public class ConnectOnboardingResponse {
    private String url;
    private String object;
    private String status;
    private String stripeConnectId;
    private UUID storeId;
    private Boolean chargesEnabled;
    private Boolean payoutsEnabled;
    private String message;
}
