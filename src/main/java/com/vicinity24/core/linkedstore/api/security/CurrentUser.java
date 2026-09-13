package com.vicinity24.core.linkedstore.api.security;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUser {
    private UUID userId;
    private String email;
    private UUID storeId;
    private UserRole role;
    private boolean globalAdmin;
    private boolean authenticated;

    public boolean canManageStore(UUID targetStoreId) {
        if (!authenticated) return false;
        if (globalAdmin) return true;
        if (targetStoreId == null) return false;
        if (storeId == null) return false;
        return storeId.equals(targetStoreId)
                && (role == UserRole.STORE_ADMIN || role == UserRole.OWNER);
    }

    public boolean isAtLeastStoreAdmin() {
        if (!authenticated) return false;
        if (globalAdmin) return true;
        return role == UserRole.STORE_ADMIN || role == UserRole.OWNER;
    }

    public String roleName() {
        return role == null ? UserRole.CLERK.name() : role.name();
    }
}
