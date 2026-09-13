package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.CheckAvailabilityRequest;
import com.vicinity24.core.linkedstore.api.dto.CheckAvailabilityResponse;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/check-availability")
    public ResponseEntity<CheckAvailabilityResponse> checkAvailability(
            @Valid @RequestBody CheckAvailabilityRequest request) {

        log.info("Inventory availability check requested: originatingStore={}, product={}, variant={}",
                request.getOriginatingStoreId(), request.getProductId(), request.getVariantId());

        CheckAvailabilityResponse response = inventoryService.checkAvailabilityAndLock(request);

        log.info("Inventory reserved: lockId={}, txId={}, expiresAt={}",
                response.getInventoryLockId(), response.getTransactionId(), response.getExpiresAt());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/locks/{transactionId}")
    public ResponseEntity<CheckAvailabilityResponse> getLockStatus(
            @PathVariable UUID transactionId) {
        CheckAvailabilityResponse response = inventoryService.getLockStatus(transactionId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/variants/search")
    public ResponseEntity<List<ProductVariant>> searchNearbyVariants(
            @RequestParam UUID productId,
            @RequestParam UUID excludeStoreId,
            @RequestParam(required = false) Map<String, Object> attributesFilter,
            @RequestParam(defaultValue = "1") int minQuantity) {

        List<ProductVariant> variants = inventoryService.searchNearbyVariants(
                productId, excludeStoreId, attributesFilter, minQuantity);

        return ResponseEntity.ok(variants);
    }
}
