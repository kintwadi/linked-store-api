package com.vicinity24.core.linkedstore.api.exception;

import lombok.Getter;

@Getter
public class ImageStorageException extends RuntimeException {

    private final String reason;

    public ImageStorageException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    public ImageStorageException(String message, String reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
