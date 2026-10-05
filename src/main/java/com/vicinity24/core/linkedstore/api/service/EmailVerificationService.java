package com.vicinity24.core.linkedstore.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy placeholder retained for reference only. Email verification flows are handled
 * directly by AuthController / EmailService without a separate token repository today.
 * This class is NOT a Spring bean and has no callers.
 */
public final class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private EmailVerificationService() {
        // utility / reference class
    }

    public static boolean isPlaceholder() {
        log.debug("EmailVerificationService placeholder referenced");
        return true;
    }
}
