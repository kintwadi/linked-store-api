package com.vicinity24.core.linkedstore.api.security.permission;

import java.util.UUID;

public class PermissionDeniedException extends RuntimeException {

    private final String action;
    private final UUID targetId;
    private final String code;

    public PermissionDeniedException(String action, UUID targetId, String code) {
        super("Not permitted to " + action + " " + targetId + " (" + code + ")");
        this.action = action;
        this.targetId = targetId;
        this.code = code;
    }

    public String getAction() {
        return action;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getCode() {
        return code;
    }
}
