package com.vicinity24.core.linkedstore.api.service;

import com.vicinity24.core.linkedstore.api.config.CloudflareR2Config;
import com.vicinity24.core.linkedstore.api.config.CloudflareR2Properties;
import com.vicinity24.core.linkedstore.api.exception.ImageStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ImageStorageService {

    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/gif", "image/avif"
    );
    private static final List<String> ALLOWED_EXTENSIONS =
            List.of("jpg", "jpeg", "png", "webp", "gif", "avif");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<S3Client> s3ClientProvider;
    private final CloudflareR2Properties r2;
    private final CloudflareR2Config r2Config;

    public ImageStorageService(ObjectProvider<S3Client> s3ClientProvider,
                               CloudflareR2Properties r2,
                               CloudflareR2Config r2Config) {
        this.s3ClientProvider = s3ClientProvider;
        this.r2 = r2;
        this.r2Config = r2Config;
    }

    public static class UploadResult {
        private final String key;
        private final String publicUrl;
        private final String contentType;
        private final long sizeBytes;

        public UploadResult(String key, String publicUrl, String contentType, long sizeBytes) {
            this.key = key;
            this.publicUrl = publicUrl;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
        }

        public String key() { return key; }
        public String publicUrl() { return publicUrl; }
        public String contentType() { return contentType; }
        public long sizeBytes() { return sizeBytes; }
    }

    public UploadResult uploadProductImage(UUID storeId, UUID productId, MultipartFile file) {
        assertR2Configured();
        validateFile(file);
        String folder = "stores/%s/products/%s".formatted(storeId, productId);
        return uploadInternal(folder, file);
    }

    public UploadResult uploadStoreLogo(UUID storeId, MultipartFile file) {
        assertR2Configured();
        validateFile(file);
        String folder = "stores/%s/logo".formatted(storeId);
        return uploadInternal(folder, file);
    }

    public UploadResult uploadVariantImage(UUID storeId, UUID variantId, MultipartFile file) {
        assertR2Configured();
        validateFile(file);
        String folder = "stores/%s/variants/%s".formatted(storeId, variantId);
        return uploadInternal(folder, file);
    }

    public UploadResult uploadRunnerProfilePicture(UUID storeId, UUID runnerId, MultipartFile file) {
        assertR2Configured();
        validateFile(file);
        String folder = "stores/%s/runners/%s".formatted(storeId, runnerId);
        return uploadInternal(folder, file);
    }

    public void deleteImage(String key) {
        S3Client s3Client = requireS3Client();
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(r2.bucketName())
                    .key(key)
                    .build());
            log.info("Deleted R2 object: bucket={} key={}", r2.bucketName(), key);
        } catch (Exception e) {
            log.error("Failed to delete R2 object: bucket={} key={}", r2.bucketName(), key, e);
            throw new ImageStorageException("Failed to delete image", "DELETE_FAILED", e);
        }
    }

    public String buildPublicUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String base = r2.publicUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String trimmedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String trimmedKey = key.startsWith("/") ? key.substring(1) : key;
        return "%s/%s".formatted(trimmedBase, trimmedKey);
    }

    private UploadResult uploadInternal(String folder, MultipartFile file) {
        S3Client s3Client = requireS3Client();
        String extension = resolveExtension(file);
        String contentType = file.getContentType();
        long size = file.getSize();
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String nonce = randomNonce();
        String fileName = sanitizeOriginalName(file.getOriginalFilename());
        String key = "%s/%s/%s_%s.%s".formatted(folder, datePart, nonce, fileName, extension);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(r2.bucketName())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(size)
                    .cacheControl("public, max-age=31536000, immutable")
                    .build();

            byte[] bytes = file.getBytes();
            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromBytes(bytes));

            log.info("R2 upload OK: bucket={} key={} eTag={} size={}b type={}",
                    r2.bucketName(), key, response.eTag(), size, contentType);

            return new UploadResult(key, buildPublicUrl(key), contentType, size);
        } catch (IOException e) {
            log.error("Failed to read multipart file for R2 upload: key={}", key, e);
            throw new ImageStorageException("Failed to read uploaded file", "FILE_READ_ERROR", e);
        } catch (Exception e) {
            log.error("R2 upload failed: bucket={} key={}", r2.bucketName(), key, e);
            throw new ImageStorageException("Image storage service failed", "UPLOAD_FAILED", e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImageStorageException("Uploaded file is empty", "EMPTY_FILE");
        }
        long maxBytes = r2.imageMaxBytes() > 0 ? r2.imageMaxBytes() : 20 * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new ImageStorageException(
                    "File too large (max %d bytes)".formatted(maxBytes),
                    "FILE_TOO_LARGE");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ImageStorageException(
                    "Content type %s is not allowed. Allowed: %s".formatted(contentType, ALLOWED_IMAGE_CONTENT_TYPES),
                    "CONTENT_TYPE_NOT_ALLOWED");
        }
        String ext = resolveExtension(file);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ImageStorageException(
                    "Extension %s is not allowed. Allowed: %s".formatted(ext, ALLOWED_EXTENSIONS),
                    "EXTENSION_NOT_ALLOWED");
        }
    }

    private String resolveExtension(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (StringUtils.hasText(original)) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && dot < original.length() - 1) {
                return original.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        String ct = file.getContentType();
        if (ct == null) return "bin";
        return switch (ct.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            default -> "bin";
        };
    }

    private static String sanitizeOriginalName(String original) {
        if (!StringUtils.hasText(original)) {
            return "image";
        }
        int dot = original.lastIndexOf('.');
        String base = dot >= 0 ? original.substring(0, dot) : original;
        String cleaned = base.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return cleaned.isEmpty() ? "image" : cleaned.substring(0, Math.min(cleaned.length(), 40));
    }

    private static String randomNonce() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        String ts = Long.toHexString(Instant.now().toEpochMilli());
        return ts + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void assertR2Configured() {
        requireS3Client();
    }

    private S3Client requireS3Client() {
        S3Client client = s3ClientProvider.getIfAvailable();
        if (!r2Config.isConfigured() || client == null) {
            throw new ImageStorageException(
                    "Cloudflare R2 is not configured. Run setup.bat before starting the application, "
                            + "or copy every R2_* environment variable from setup.bat into your IDE Run "
                            + "Configuration -> Environment Variables.",
                    "R2_NOT_CONFIGURED");
        }
        return client;
    }
}
