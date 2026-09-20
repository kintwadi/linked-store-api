package com.vicinity24.core.linkedstore.api.security.permission;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final StorePermissionGuard storeGuard;
    private final AdminPermissionGuard adminGuard;

    // ---------- Store scoped ----------

    public boolean canViewStore(CurrentUser caller, UUID targetStoreId) {
        return storeGuard.canViewStore(caller, targetStoreId);
    }

    public void ensureViewStore(CurrentUser caller, UUID targetStoreId) {
        if (!canViewStore(caller, targetStoreId)) {
            throw new PermissionDeniedException("view-store", targetStoreId, "ILLEGAL_STORE_VIEW");
        }
    }

    public boolean canViewProductsOfStore(CurrentUser caller, UUID storeId) {
        return storeGuard.canViewProductsOfStore(caller, storeId);
    }

    public void ensureViewProductsOfStore(CurrentUser caller, UUID storeId) {
        if (!canViewProductsOfStore(caller, storeId)) {
            throw new PermissionDeniedException("view-products", storeId, "ILLEGAL_PRODUCT_VIEW");
        }
    }

    public boolean canCreateProduct(CurrentUser caller, UUID storeId) {
        return storeGuard.canCreateProduct(caller, storeId);
    }

    public void ensureCreateProduct(CurrentUser caller, UUID storeId) {
        if (!canCreateProduct(caller, storeId)) {
            throw new PermissionDeniedException("create-product", storeId, "ILLEGAL_PRODUCT_CREATE");
        }
    }

    public boolean canEditProduct(CurrentUser caller, UUID productStoreId) {
        return storeGuard.canEditProduct(caller, productStoreId);
    }

    public void ensureEditProduct(CurrentUser caller, UUID productStoreId) {
        if (!canEditProduct(caller, productStoreId)) {
            throw new PermissionDeniedException("edit-product", productStoreId, "ILLEGAL_STORE_WRITE");
        }
    }

    public boolean canDeleteProduct(CurrentUser caller, UUID productStoreId) {
        return storeGuard.canDeleteProduct(caller, productStoreId);
    }

    public void ensureDeleteProduct(CurrentUser caller, UUID productStoreId) {
        if (!canDeleteProduct(caller, productStoreId)) {
            throw new PermissionDeniedException("delete-product", productStoreId, "ILLEGAL_STORE_WRITE");
        }
    }

    public boolean canGenerateQrForProduct(CurrentUser caller, UUID productStoreId) {
        return storeGuard.canGenerateQrForProduct(caller, productStoreId);
    }

    public void ensureGenerateQrForProduct(CurrentUser caller, UUID productStoreId) {
        if (!canGenerateQrForProduct(caller, productStoreId)) {
            throw new PermissionDeniedException("generate-qr", productStoreId, "ILLEGAL_QR_GENERATE");
        }
    }

    public boolean canInviteRole(CurrentUser caller, UUID storeId, UserRole targetRole) {
        return storeGuard.canInviteRole(caller, storeId, targetRole);
    }

    public void ensureCanInviteRole(CurrentUser caller, UUID storeId, UserRole targetRole) {
        if (!canInviteRole(caller, storeId, targetRole)) {
            throw new PermissionDeniedException("invite-role", storeId, "ILLEGAL_INVITE_HIERARCHY");
        }
    }

    public boolean canManageUsers(CurrentUser caller, UUID storeId) {
        return storeGuard.canManageUsers(caller, storeId);
    }

    public void ensureCanManageUsers(CurrentUser caller, UUID storeId) {
        if (!canManageUsers(caller, storeId)) {
            throw new PermissionDeniedException("manage-users", storeId, "ILLEGAL_USER_MANAGE");
        }
    }

    public boolean canManagePayouts(CurrentUser caller, UUID storeId) {
        return storeGuard.canManagePayouts(caller, storeId);
    }

    public void ensureCanManagePayouts(CurrentUser caller, UUID storeId) {
        if (!canManagePayouts(caller, storeId)) {
            throw new PermissionDeniedException("manage-payouts", storeId, "ILLEGAL_PAYOUTS_MANAGE");
        }
    }

    public boolean canViewTransactions(CurrentUser caller, UUID storeId) {
        return storeGuard.canViewTransactions(caller, storeId);
    }

    public void ensureCanViewTransactions(CurrentUser caller, UUID storeId) {
        if (!canViewTransactions(caller, storeId)) {
            throw new PermissionDeniedException("view-transactions", storeId, "ILLEGAL_TRANSACTION_VIEW");
        }
    }

    // ---------- Admin scoped ----------

    public boolean isGlobalAdmin(CurrentUser caller) {
        return adminGuard.isGlobalAdmin(caller);
    }

    public void ensureGlobalAdmin(CurrentUser caller) {
        if (!isGlobalAdmin(caller)) {
            throw new PermissionDeniedException("global-admin", null, "GLOBAL_ADMIN_REQUIRED");
        }
    }

    public boolean canCreateStore(CurrentUser caller) {
        return adminGuard.canCreateStore(caller);
    }

    public void ensureCanCreateStore(CurrentUser caller) {
        if (!canCreateStore(caller)) {
            throw new PermissionDeniedException("create-store", null, "STORE_CREATE_UNAUTHORIZED");
        }
    }

    public boolean canEditStoreSettings(CurrentUser caller, UUID storeId) {
        return adminGuard.canEditStoreSettings(caller, storeId);
    }

    public boolean canManageStoreRequests(CurrentUser caller, UUID storeId) {
        return adminGuard.canManageStoreRequests(caller, storeId);
    }

    public void ensureCanManageStoreRequests(CurrentUser caller, UUID storeId) {
        if (!canManageStoreRequests(caller, storeId)) {
            throw new PermissionDeniedException("manage-store-requests", storeId, "ILLEGAL_STORE_REQUEST_MANAGE");
        }
    }

    public void ensureCanEditStoreSettings(CurrentUser caller, UUID storeId) {
        if (!canEditStoreSettings(caller, storeId)) {
            throw new PermissionDeniedException("edit-store-settings", storeId, "ILLEGAL_STORE_SETTINGS_EDIT");
        }
    }

    public boolean canPromoteRoleTo(CurrentUser caller, UserRole targetRole) {
        return adminGuard.canPromoteRoleTo(caller, targetRole);
    }

    public void ensureCanPromoteRoleTo(CurrentUser caller, UserRole targetRole) {
        if (!canPromoteRoleTo(caller, targetRole)) {
            throw new PermissionDeniedException("promote-role", null, "ILLEGAL_PROMOTE_HIERARCHY");
        }
    }

    public boolean isAtLeastRole(CurrentUser caller, UserRole minimum) {
        return adminGuard.isAtLeastRole(caller, minimum);
    }

    public void ensureAtLeastRole(CurrentUser caller, UserRole minimum) {
        if (!isAtLeastRole(caller, minimum)) {
            throw new PermissionDeniedException("role-at-least-" + minimum, null, "INSUFFICIENT_ROLE");
        }
    }
}
