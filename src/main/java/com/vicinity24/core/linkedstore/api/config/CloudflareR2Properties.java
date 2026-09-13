package com.vicinity24.core.linkedstore.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudflare.r2")
public record CloudflareR2Properties(
        String accountId,
        String accessKeyId,
        String secretAccessKey,
        String bucketName,
        String endpoint,
        String publicUrl,
        long imageMaxBytes
) {
}
