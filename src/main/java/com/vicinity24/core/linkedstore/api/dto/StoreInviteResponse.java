package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreInviteResponse {

    private String inviteToken;
    private String redeemUrl;
    private OffsetDateTime expiresAt;
    private String targetRole;
    private String prefillEmail;
    private String status;
}
