package com.vicinity24.core.linkedstore.api.security.permission;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Guard for store-scoped authorization rules.
 * Covers: view store + view products across stores, create/edit/delete products within own store,
 * generate QR codes for products (cross-store for REP+), invite hierarchy within a store,
 * manage users/payouts scoped to store, redeem invites.
 */
@Component
class StorePermissionGuard {

    boolean canViewStore(CurrentUser caller, UUID targetStoreId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (targetStoreId == null) return false;
        UserRole role = caller.getRole();
        if (role == UserRole.OWNER || role == UserRole.STORE_ADMIN) {
            return caller.getStoreId() != null && caller.getStoreId().equals(targetStoreId);
        }
        if (role == UserRole.STORE_REPRESENTATIVE) {
            return true;
        }
        if (role == UserRole.CLERK || role == UserRole.RUNNER) {
            return caller.getStoreId() != null && caller.getStoreId().equals(targetStoreId);
        }
        return false;
    }

    boolean canViewProductsOfStore(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        UserRole role = caller.getRole();
        if (role == UserRole.OWNER
                || role == UserRole.STORE_ADMIN
                || role == UserRole.STORE_REPRESENTATIVE
                || role == UserRole.CLERK
                || role == UserRole.RUNNER) {
            return true;
        }
        return false;
    }

    boolean canCreateProduct(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null || caller.getStoreId() == null) return false;
        if (!caller.getStoreId().equals(storeId)) return false;
        UserRole role = caller.getRole();
        return role == UserRole.OWNER
                || role == UserRole.STORE_ADMIN
                || role == UserRole.STORE_REPRESENTATIVE;
    }

    boolean canEditProduct(CurrentUser caller, UUID productStoreId) {
        return canCreateProduct(caller, productStoreId);
    }

    boolean canDeleteProduct(CurrentUser caller, UUID productStoreId) {
        return canCreateProduct(caller, productStoreId);
    }

    boolean canGenerateQrForProduct(CurrentUser caller, UUID productStoreId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        UserRole role = caller.getRole();
        if (role == UserRole.OWNER
                || role == UserRole.STORE_ADMIN
                || role == UserRole.STORE_REPRESENTATIVE
                || role == UserRole.CLERK
                || role == UserRole.RUNNER) {
            return true;
        }
        return false;
    }

    boolean canInviteRole(CurrentUser caller, UUID storeId, UserRole targetRole) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (targetRole == null) return false;
        if (caller.isGlobalAdmin()) {
            return true;
        }
        if (storeId == null || caller.getStoreId() == null
                || !caller.getStoreId().equals(storeId)) {
            return false;
        }
        int callerRank = AuthenticationFacade.roleRankOf(caller.getRole());
        int targetRank = AuthenticationFacade.roleRankOf(targetRole);
        return callerRank > targetRank;
    }

    boolean canManageUsers(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null || caller.getStoreId() == null
                || !caller.getStoreId().equals(storeId)) {
            return false;
        }
        UserRole role = caller.getRole();
        return role == UserRole.OWNER || role == UserRole.STORE_ADMIN;
    }

    boolean canManagePayouts(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null || caller.getStoreId() == null
                || !caller.getStoreId().equals(storeId)) {
            return false;
        }
        UserRole role = caller.getRole();
        return role == UserRole.OWNER || role == UserRole.STORE_ADMIN;
    }

    boolean canViewTransactions(CurrentUser caller, UUID storeId) {
        return canViewStore(caller, storeId);
    }
}
