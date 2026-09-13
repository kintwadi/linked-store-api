package com.vicinity24.core.linkedstore.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "linkedstore.auth")
public class AuthProperties {

    private String jwtSecret = "change-me-please-set-LINKEDSTORE_AUTH_JWT_SECRET-env-var-at-least-64-bytes-for-hs512-secret-key-0000000000000000";
    private long accessTokenMinutes = 30L;
    private long refreshTokenDays = 7L;
    private String issuer = "linked-store";
    private String defaultAdminEmail = "admin@linked.store";
    private String defaultAdminPassword = "Admin123!";
}
