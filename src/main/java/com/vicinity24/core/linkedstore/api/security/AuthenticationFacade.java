package com.vicinity24.core.linkedstore.api.security;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthenticationFacade {

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

    public void requireGlobalAdmin() {
        if (!current().isGlobalAdmin()) {
            throw new SecurityException("Global admin privileges are required.");
        }
    }

    public void requireStoreAdminOrOwner(UUID targetStoreId) {
        CurrentUser u = current();
        if (!u.isAtLeastStoreAdmin()) {
            throw new SecurityException("Store admin or owner privileges are required.");
        }
        if (!u.isGlobalAdmin() && targetStoreId != null
                && (u.getStoreId() == null || !u.getStoreId().equals(targetStoreId))) {
            throw new SecurityException("You may not manage another store.");
        }
    }

    public void requireAtLeastRole(UserRole minimum) {
        CurrentUser u = current();
        if (!u.isAuthenticated()) throw new SecurityException("Authentication required.");
        if (u.isGlobalAdmin()) return;
        if (rank(u.getRole()) < rank(minimum)) {
            throw new SecurityException("Insufficient role.");
        }
    }

    private static int rank(UserRole r) {
        if (r == null) return 0;
        return switch (r) {
            case GLOBAL_ADMIN -> 100;
            case OWNER -> 50;
            case STORE_ADMIN -> 45;
            case CLERK -> 20;
            case RUNNER -> 10;
        };
    }

    private static CurrentUser anonymous() {
        return CurrentUser.builder()
                .authenticated(false)
                .globalAdmin(false)
                .role(UserRole.CLERK)
                .build();
    }
}
