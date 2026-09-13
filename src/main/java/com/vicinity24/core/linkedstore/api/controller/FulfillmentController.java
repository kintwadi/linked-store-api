package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.VerifyPickupRequest;
import com.vicinity24.core.linkedstore.api.dto.VerifyPickupResponse;
import com.vicinity24.core.linkedstore.api.service.FulfillmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/fulfillment")
@RequiredArgsConstructor
public class FulfillmentController {

    private final FulfillmentService fulfillmentService;

    @PostMapping("/verify-pickup")
    public ResponseEntity<VerifyPickupResponse> verifyPickup(
            @RequestBody VerifyPickupRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {

        if (deviceId != null && !deviceId.isBlank() && request.getDeviceId() == null) {
            request.setDeviceId(deviceId);
        }

        String token = request.getSecureToken() != null && !request.getSecureToken().isBlank()
                ? request.getSecureToken()
                : request.getFallbackCode();
        log.info("Pickup verification requested by user={} [device={}] on token prefix={}",
                request.getScanningUserId(),
                request.getDeviceId(),
                maskToken(token));

        VerifyPickupResponse response = fulfillmentService.verifyPickup(request);

        log.info("Pickup verified: tx={}, status={}, scannedAt={}",
                response.getTransactionId(),
                response.getTransactionStatus(),
                response.getScannedAt());

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/qr/{transactionId}")
    public ResponseEntity<Map<String, Object>> getQrDetails(@PathVariable UUID transactionId) {
        var qr = fulfillmentService.getQrTokenByTransaction(transactionId);
        Map<String, Object> body = Map.of(
                "qr_token_id", qr.getId(),
                "secure_token", qr.getSecureToken(),
                "fallback_code", qr.getFallbackCode() != null ? qr.getFallbackCode() : "",
                "transaction_id", qr.getTransactionId(),
                "runner_id", qr.getRunnerId(),
                "expires_at", qr.getExpiresAt(),
                "scanned_at", qr.getScannedAt() != null ? qr.getScannedAt() : "NOT_SCANNED",
                "valid", fulfillmentService.isTokenValid(qr.getSecureToken())
        );
        return ResponseEntity.ok(body);
    }

    private String maskToken(String token) {
        if (token == null) return null;
        if (token.length() <= 10) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
