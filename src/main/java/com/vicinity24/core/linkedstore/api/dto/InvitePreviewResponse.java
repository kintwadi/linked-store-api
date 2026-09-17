package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvitePreviewResponse {

    private Boolean valid;
    private OffsetDateTime expiresAt;
    private TargetStoreInfo targetStore;
    private String targetRole;
    private String prefillEmail;
    private String errorMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetStoreInfo {
        private UUID id;
        private String businessName;
        private String logoUrl;
        private String countryCode;
        private String currencyCode;
    }
}
