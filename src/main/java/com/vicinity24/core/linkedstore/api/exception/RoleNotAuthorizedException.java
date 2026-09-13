package com.vicinity24.core.linkedstore.api.exception;

import com.vicinity24.core.linkedstore.api.entity.StoreUserRole;
import lombok.Getter;

import java.util.Set;
import java.util.UUID;

@Getter
public class RoleNotAuthorizedException extends RuntimeException {

    private final UUID userId;
    private final StoreUserRole actualRole;
    private final Set<StoreUserRole> allowedRoles;

    public RoleNotAuthorizedException(String message, UUID userId,
                                      StoreUserRole actualRole, Set<StoreUserRole> allowedRoles) {
        super(message);
        this.userId = userId;
        this.actualRole = actualRole;
        this.allowedRoles = allowedRoles;
    }
}
