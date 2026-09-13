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
public class CurrentUserResponse {

    private UUID id;
    private String email;
    private String name;
    private String phoneNumber;
    private String role;
    private UUID storeId;
    private Boolean isGlobalAdmin;
    private String status;
    private String lastLoginAt;
    private String createdAt;
}
