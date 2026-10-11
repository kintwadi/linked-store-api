package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.entity.PlatformContract;
import com.vicinity24.core.linkedstore.api.entity.PlatformContractMode;
import com.vicinity24.core.linkedstore.api.service.PlatformContractService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/platform-contract")
@RequiredArgsConstructor
@CrossOrigin(value = "*", maxAge = 3600)
public class PlatformContractController {

    private final PlatformContractService contractService;

    @GetMapping("/public")
    public ResponseEntity<Map<String, Object>> getPublicContract() {
        PlatformContract c = contractService.getEffectiveContract();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", c.getMode().name());
        body.put("feePercentage", c.getFeePercentage());
        body.put("isConfigured", contractService.isConfigured());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/admin")
    public ResponseEntity<PlatformContractAdminResponse> getAdminContract() {
        PlatformContract c = contractService.getEffectiveContract();
        return ResponseEntity.ok(toAdminResponse(c));
    }

    @PutMapping("/admin")
    public ResponseEntity<PlatformContractAdminResponse> updateAdminContract(
            @RequestBody PlatformContractUpdateRequest request) {
        PlatformContractMode mode;
        try {
            mode = PlatformContractMode.valueOf(request.getMode());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
        if (mode == PlatformContractMode.FEE_BASED
                && (request.getFeePercentage() == null
                    || request.getFeePercentage() < 0
                    || request.getFeePercentage() > 100)) {
            return ResponseEntity.badRequest().build();
        }
        PlatformContract updated = contractService.updateContract(
                mode, request.getFeePercentage(), request.getPlatformPayoutConnectId());
        return ResponseEntity.ok(toAdminResponse(updated));
    }

    private PlatformContractAdminResponse toAdminResponse(PlatformContract c) {
        return PlatformContractAdminResponse.builder()
                .mode(c.getMode().name())
                .feePercentage(c.getFeePercentage())
                .isConfigured(contractService.isConfigured())
                .platformPayoutConnectId(c.getPlatformPayoutConnectId())
                .updatedAt(c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformContractAdminResponse {
        private String mode;
        private Double feePercentage;
        private Boolean isConfigured;
        private String platformPayoutConnectId;
        private String updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformContractUpdateRequest {
        private String mode;
        private Double feePercentage;
        private String platformPayoutConnectId;
    }
}
