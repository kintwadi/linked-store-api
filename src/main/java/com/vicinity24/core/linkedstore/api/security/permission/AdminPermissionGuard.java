package com.vicinity24.core.linkedstore.api.security.permission;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Guard for cross-store and global-scoped authorization rules.
 * Covers: isGlobalAdmin check, create new store (admin-API only), edit store settings
 * (Stripe onboarding / subscription), promote role to a target level (hierarchy check).
 */
@Component
class AdminPermissionGuard {

    boolean isGlobalAdmin(CurrentUser caller) {
        return caller != null && caller.isAuthenticated() && caller.isGlobalAdmin();
    }

    boolean canCreateStore(CurrentUser caller) {
        return isGlobalAdmin(caller);
    }

    boolean canEditStoreSettings(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null || caller.getStoreId() == null
                || !caller.getStoreId().equals(storeId)) {
            return false;
        }
        UserRole role = caller.getRole();
        return role == UserRole.OWNER || role == UserRole.STORE_ADMIN;
    }

    boolean canPromoteRoleTo(CurrentUser caller, UserRole targetRole) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (targetRole == null) return false;
        if (caller.isGlobalAdmin()) {
            return true;
        }
        int callerRank = AuthenticationFacade.roleRankOf(caller.getRole());
        int targetRank = AuthenticationFacade.roleRankOf(targetRole);
        return callerRank > targetRank;
    }

    boolean isAtLeastRole(CurrentUser caller, UserRole minimum) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        int callerRank = AuthenticationFacade.roleRankOf(caller.getRole());
        int minRank = AuthenticationFacade.roleRankOf(minimum);
        return callerRank >= minRank;
    }
}
