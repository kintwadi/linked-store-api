package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.ImageUploadResponse;
import com.vicinity24.core.linkedstore.api.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageStorageService imageStorageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> uploadGeneric(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scope") String scope,
            @RequestParam("store_id") UUID storeId,
            @RequestParam(value = "parent_id", required = false) UUID parentId) {

        ImageStorageService.UploadResult result = switch (scope.toLowerCase()) {
            case "store_logo" -> imageStorageService.uploadStoreLogo(storeId, file);
            case "product" -> {
                if (parentId == null) {
                    throw new IllegalArgumentException("parent_id is required for scope=product");
                }
                yield imageStorageService.uploadProductImage(storeId, parentId, file);
            }
            case "variant" -> {
                if (parentId == null) {
                    throw new IllegalArgumentException("parent_id is required for scope=variant");
                }
                yield imageStorageService.uploadVariantImage(storeId, parentId, file);
            }
            case "runner" -> {
                if (parentId == null) {
                    throw new IllegalArgumentException("parent_id is required for scope=runner");
                }
                yield imageStorageService.uploadRunnerProfilePicture(storeId, parentId, file);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown scope '" + scope + "'. Allowed: store_logo, product, variant, runner");
        };

        ImageUploadResponse body = ImageUploadResponse.builder()
                .key(result.key())
                .publicUrl(result.publicUrl())
                .contentType(result.contentType())
                .sizeBytes(result.sizeBytes())
                .uploadedAt(OffsetDateTime.now())
                .scope(scope)
                .parentId(parentId != null ? parentId.toString() : storeId.toString())
                .build();

        log.info("Image upload OK: scope={} store={} key={} url={}",
                scope, storeId, result.key(), result.publicUrl());

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping(value = "/store-logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> uploadStoreLogo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("store_id") UUID storeId) {
        ImageStorageService.UploadResult r = imageStorageService.uploadStoreLogo(storeId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(r, "store_logo", storeId));
    }

    @PostMapping(value = "/product", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> uploadProductImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("store_id") UUID storeId,
            @RequestParam("product_id") UUID productId) {
        ImageStorageService.UploadResult r = imageStorageService.uploadProductImage(storeId, productId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(r, "product", productId));
    }

    @PostMapping(value = "/variant", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> uploadVariantImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("store_id") UUID storeId,
            @RequestParam("variant_id") UUID variantId) {
        ImageStorageService.UploadResult r = imageStorageService.uploadVariantImage(storeId, variantId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(r, "variant", variantId));
    }

    @DeleteMapping("/{keyPrefix}/{keyDate}/{keyName}")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable String keyPrefix,
            @PathVariable String keyDate,
            @PathVariable String keyName) {
        String key = "%s/%s/%s".formatted(keyPrefix, keyDate, keyName);
        imageStorageService.deleteImage(key);
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "key", key
        ));
    }

    @DeleteMapping("/by-key")
    public ResponseEntity<Map<String, Object>> deleteImageByKey(@RequestBody Map<String, String> payload) {
        String key = payload.get("key");
        imageStorageService.deleteImage(key);
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "key", key
        ));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "max_upload_mb", 20,
                "allowed_types", "image/jpeg,image/png,image/webp,image/gif,image/avif",
                "supported_scopes", "store_logo,product,variant,runner"
        ));
    }

    private static ImageUploadResponse toResponse(ImageStorageService.UploadResult r, String scope, UUID parentId) {
        return ImageUploadResponse.builder()
                .key(r.key())
                .publicUrl(r.publicUrl())
                .contentType(r.contentType())
                .sizeBytes(r.sizeBytes())
                .uploadedAt(OffsetDateTime.now())
                .scope(scope)
                .parentId(parentId.toString())
                .build();
    }
}
