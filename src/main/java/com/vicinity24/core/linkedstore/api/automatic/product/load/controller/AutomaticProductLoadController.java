package com.vicinity24.core.linkedstore.api.automatic.product.load.controller;

import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadReport;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import com.vicinity24.core.linkedstore.api.automatic.product.load.service.AutomaticProductLoadService;
import com.vicinity24.core.linkedstore.api.automatic.product.load.service.ProductTemplateService;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.security.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/stores/{storeId}/products/upload")
@RequiredArgsConstructor
public class AutomaticProductLoadController {

    private final AutomaticProductLoadService service;
    private final ProductTemplateService templates;
    private final StoreRepository storeRepository;
    private final AuthenticationFacade authenticationFacade;
    private final PermissionService permissionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @PathVariable String storeId,
            @RequestParam(value = "file", required = true) MultipartFile file,
            @RequestParam(value = "format", required = false) String explicitFormat
    ) {
        UUID storeUuid;
        try {
            storeUuid = UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(badRequest("Invalid storeId UUID."));
        }
        Store store = storeRepository.findById(storeUuid).orElse(null);
        if (store == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(badRequest("Store not found."));
        }
        CurrentUser user = authenticationFacade.current();
        if (!permissionService.canCreateProduct(user, storeUuid)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(badRequest("You do not have permission to upload products for this store."));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(badRequest("No file provided."));
        }
        long size = file.getSize();
        if (size > 100L * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(badRequest("File too large — maximum 100 MB."));
        }

        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String format = (explicitFormat != null && !explicitFormat.isBlank())
                ? explicitFormat.trim().toLowerCase()
                : service.detectFormat(fileName, file);

        List<ProductLoadRequest> rows;
        try {
            rows = service.parse(file, format);
        } catch (Exception ex) {
            log.warn("AutomaticProductLoadController: parse failure for store {} file {} format {}", storeId, fileName, format, ex);
            return ResponseEntity.badRequest().body(badRequest(
                    "Failed to parse the " + format.toUpperCase() + " file. Please verify it matches the downloadable template. " +
                            "Details: " + ex.getMessage()));
        }
        if (rows == null || rows.isEmpty()) {
            return ResponseEntity.badRequest().body(badRequest("No product rows were found in the file."));
        }

        ProductLoadReport report;
        try {
            report = service.loadForStore(storeUuid, rows, format);
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(badRequest(iae.getMessage()));
        } catch (Exception ex) {
            log.error("AutomaticProductLoadController: loadForStore failed for store {} file {}", storeId, fileName, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(badRequest("Unexpected error while importing: " + ex.getMessage()));
        }

        return ResponseEntity.ok(report);
    }

    // ======================= Templates =======================

    @GetMapping(value = "/templates/json", produces = "application/json")
    public ResponseEntity<byte[]> templateJson() throws Exception {
        String body = templates.jsonTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-template.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping(value = "/templates/csv", produces = "text/csv")
    public ResponseEntity<byte[]> templateCsv() throws Exception {
        String body = templates.csvTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping(value = "/templates/xml", produces = "application/xml")
    public ResponseEntity<byte[]> templateXml() throws Exception {
        String body = templates.xmlTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-template.xml\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private static java.util.Map<String, Object> badRequest(String message) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("timestamp", java.time.OffsetDateTime.now().toString());
        m.put("status", 400);
        m.put("error", "Bad Request");
        m.put("message", message);
        return m;
    }
}
