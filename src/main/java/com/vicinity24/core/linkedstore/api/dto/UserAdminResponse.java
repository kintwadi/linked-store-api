package com.vicinity24.core.linkedstore.api.dto;

import com.vicinity24.core.linkedstore.api.entity.UserAccount;
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
public class UserAdminResponse {

    private UUID id;
    private UUID storeId;
    private String storeName;
    private String name;
    private String email;
    private String phone;
    private String role;
    private String status;
    private Boolean isGlobalAdmin;
    private Boolean hasPassword;
    private String lastLoginAt;
    private String createdAt;
    private String updatedAt;

    public static UserAdminResponse from(UserAccount user, String storeName) {
        return UserAdminResponse.builder()
                .id(user.getId())
                .storeId(user.getStoreId())
                .storeName(storeName)
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhoneNumber())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .isGlobalAdmin(user.isGlobalAdminEffective())
                .hasPassword(user.hasPassword())
                .lastLoginAt(user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null)
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .updatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null)
                .build();
    }
}
