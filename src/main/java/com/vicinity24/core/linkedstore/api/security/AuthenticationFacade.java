package com.vicinity24.core.linkedstore.api.security;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.security.permission.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthenticationFacade {

    private PermissionService permissionService;

    @Autowired
    public void setPermissionService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return anonymous();
        Object principal = auth.getPrincipal();
        if (principal instanceof CurrentUser cu) return cu;
        return anonymous();
    }

    public UUID currentStoreIdOrThrow() {
        CurrentUser u = current();
        if (u.isGlobalAdmin()) return null;
        if (u.getStoreId() == null) {
            throw new SecurityException("This operation requires a store-scoped user.");
        }
        return u.getStoreId();
    }

    public static int roleRankOf(UserRole r) {
        if (r == null) return 0;
        return switch (r) {
            case GLOBAL_ADMIN -> 100;
            case OWNER -> 80;
            case STORE_ADMIN -> 45;
            case STORE_REPRESENTATIVE -> 25;
            case CLERK -> 10;
            case RUNNER -> 5;
        };
    }

    @Deprecated(since = "permissions-module", forRemoval = false)
    public void requireGlobalAdmin() {
        permissionService.ensureGlobalAdmin(current());
    }

    @Deprecated(since = "permissions-module", forRemoval = false)
    public void requireStoreAdminOrOwner(UUID targetStoreId) {
        permissionService.ensureCanEditStoreSettings(current(), targetStoreId);
    }

    @Deprecated(since = "permissions-module", forRemoval = false)
    public void requireAtLeastRole(UserRole minimum) {
        permissionService.ensureAtLeastRole(current(), minimum);
    }

    private static CurrentUser anonymous() {
        return CurrentUser.builder()
                .authenticated(false)
                .globalAdmin(false)
                .role(UserRole.CLERK)
                .build();
    }
}
