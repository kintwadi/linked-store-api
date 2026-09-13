package com.vicinity24.core.linkedstore.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Slf4j
@Configuration
@EnableConfigurationProperties(CloudflareR2Properties.class)
public class CloudflareR2Config {

    private static final Region AUTO_REGION = Region.of("auto");

    private final CloudflareR2Properties r2;

    public CloudflareR2Config(CloudflareR2Properties r2) {
        this.r2 = r2;
    }

    @Bean
    public S3Client s3Client() {
        if (!isConfigured()) {
            log.warn("Cloudflare R2 is NOT configured (missing accountId/accessKeyId/secretAccessKey or endpoint). "
                    + "Image upload endpoints are disabled until all R2_* environment variables are exported "
                    + "via setup.bat before starting the application (IDE run configurations: copy all R2_* keys from "
                    + "setup.bat into the Run Configuration's Environment Variables panel).");
            return null;
        }
        AwsBasicCredentials credentials = AwsBasicCredentials.create(r2.accessKeyId(), r2.secretAccessKey());
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        return S3Client.builder()
                .region(AUTO_REGION)
                .endpointOverride(URI.create(r2.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(s3Configuration)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        if (!isConfigured()) {
            return null;
        }
        AwsBasicCredentials credentials = AwsBasicCredentials.create(r2.accessKeyId(), r2.secretAccessKey());
        return S3Presigner.builder()
                .region(AUTO_REGION)
                .endpointOverride(URI.create(r2.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    public boolean isConfigured() {
        return hasText(r2.accountId())
                && hasText(r2.accessKeyId())
                && hasText(r2.secretAccessKey())
                && hasText(r2.endpoint());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
