package com.vicinity24.core.linkedstore.api.security.permission;

import com.vicinity24.core.linkedstore.api.entity.StoreUser;
import com.vicinity24.core.linkedstore.api.entity.StoreUserRole;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.repository.StoreUserRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Guard for cross-store and global-scoped authorization rules.
 * Covers: isGlobalAdmin check, create new store (admin-API only), edit store settings
 * (Stripe onboarding / subscription), promote role to a target level (hierarchy check).
 *
 * For store-scoped decisions (edit settings / manage subscription / etc.), always tries
 * the JWT claim (caller.storeId + caller.role) first for the common fast path. When the
 * JWT claim doesn't match or is blank (possible for users with multiple StoreUser rows,
 * or when store_id wasn't populated on the token mint), falls back to a database lookup
 * via StoreUserRepository on (caller.userId, targetStoreId) and checks the actual stored
 * StoreUserRole on that row.
 */
@Component
class AdminPermissionGuard {

    private StoreUserRepository storeUserRepository;

    @Autowired
    public void setStoreUserRepository(StoreUserRepository storeUserRepository) {
        this.storeUserRepository = storeUserRepository;
    }

    boolean isGlobalAdmin(CurrentUser caller) {
        return caller != null && caller.isAuthenticated() && caller.isGlobalAdmin();
    }

    boolean canCreateStore(CurrentUser caller) {
        return isGlobalAdmin(caller);
    }

    boolean canEditStoreSettings(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null) return false;
        if (matchesJwtStoreAndRole(caller, storeId, UserRole.OWNER, UserRole.STORE_ADMIN)) return true;
        return hasDbStoreRole(caller, storeId, StoreUserRole.OWNER, StoreUserRole.STORE_ADMIN);
    }

    boolean canManageStoreRequests(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null) return false;
        if (matchesJwtStoreAndRole(caller, storeId,
                UserRole.OWNER, UserRole.STORE_ADMIN,
                UserRole.STORE_REPRESENTATIVE, UserRole.CLERK)) return true;
        return hasDbStoreRole(caller, storeId,
                StoreUserRole.OWNER, StoreUserRole.STORE_ADMIN,
                StoreUserRole.STORE_REPRESENTATIVE, StoreUserRole.CLERK);
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

    /**
     * Subscription management / access permission.
     * Allowed for:
     *   - Any Global Admin (can review/change subscriptions for any store).
     *   - Store Admin or OWNER acting on their own storeId (either via JWT store_id claim
     *     matching target store, OR via a real StoreUser row with OWNER/STORE_ADMIN role
     *     on (caller.userId, targetStoreId)).
     * No access for STORE_REPRESENTATIVE, CLERK, or RUNNER.
     * When storeId is null, returns true only for Global Admin (scenario: app-wide pricing page).
     */
    boolean canManageSubscription(CurrentUser caller, UUID storeId) {
        if (caller == null || !caller.isAuthenticated()) return false;
        if (caller.isGlobalAdmin()) return true;
        if (storeId == null) return false;
        if (matchesJwtStoreAndRole(caller, storeId, UserRole.OWNER, UserRole.STORE_ADMIN)) return true;
        return hasDbStoreRole(caller, storeId, StoreUserRole.OWNER, StoreUserRole.STORE_ADMIN);
    }

    // ---------- helpers ----------

    private static boolean matchesJwtStoreAndRole(CurrentUser caller, UUID target, UserRole... allowed) {
        if (caller == null || target == null) return false;
        if (caller.getStoreId() == null || !caller.getStoreId().equals(target)) return false;
        UserRole role = caller.getRole();
        if (role == null) return false;
        for (UserRole a : allowed) {
            if (a == role) return true;
        }
        return false;
    }

    private boolean hasDbStoreRole(CurrentUser caller, UUID targetStoreId, StoreUserRole... allowed) {
        if (caller == null || targetStoreId == null || storeUserRepository == null) return false;
        UUID userId = caller.getUserId();
        if (userId == null) return false;
        List<StoreUser> rows;
        try {
            rows = storeUserRepository.findByStoreId(targetStoreId);
        } catch (RuntimeException ex) {
            return false;
        }
        if (rows == null || rows.isEmpty()) return false;
        for (StoreUser su : rows) {
            if (!userId.equals(su.getId())) continue;
            StoreUserRole role = su.getRole();
            if (role == null) continue;
            for (StoreUserRole a : allowed) {
                if (a == role) return true;
            }
        }
        return false;
    }
}
