package com.vicinity24.core.linkedstore.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicinity24.core.linkedstore.api.dto.CheckAvailabilityRequest;
import com.vicinity24.core.linkedstore.api.dto.CheckAvailabilityResponse;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.exception.InsufficientStockException;
import com.vicinity24.core.linkedstore.api.exception.InventoryLockFailedException;
import com.vicinity24.core.linkedstore.api.exception.ReservationExpiredException;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final ObjectMapper objectMapper;

    @Value("${inventory.lock-minutes:15}")
    private int lockMinutes;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CheckAvailabilityResponse checkAvailabilityAndLock(CheckAvailabilityRequest request) {
        UUID originatingStoreId = request.getOriginatingStoreId();
        UUID variantId = request.getVariantId();
        int quantity = Optional.ofNullable(request.getQuantity()).orElse(1);

        Store originatingStore = storeRepository.findById(originatingStoreId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", originatingStoreId.toString()));

        ProductVariant variant = variantRepository.findByIdWithProduct(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));

        if (variant.getStoreId().equals(originatingStoreId)) {
            throw new InventoryLockFailedException(
                    "Cannot reserve inventory from the originating store itself; variant is already local");
        }

        if (variant.getStatus() != VariantStatus.ACTIVE) {
            throw new InventoryLockFailedException(
                    "Variant is not active for reservation: " + variant.getStatus());
        }

        if (variant.getStockQuantity() < quantity) {
            throw new InsufficientStockException(
                    "Not enough stock available", variantId, quantity, variant.getStockQuantity());
        }

        int decremented = variantRepository.decrementStockWithOptimisticLock(
                variantId, quantity, variant.getVersion());
        if (decremented == 0) {
            ProductVariant refreshed = variantRepository.findById(variantId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId.toString()));
            throw new InventoryLockFailedException(
                    "Concurrent inventory modification; please retry",
                    variantId, quantity, refreshed.getStockQuantity());
        }

        int wholesaleCents = variant.getWholesalePriceCents() * quantity;
        int retailCents = variant.getRetailPriceCents() * quantity;
        int marginCents = retailCents - wholesaleCents;

        Transaction transaction = Transaction.builder()
                .originatingStoreId(originatingStoreId)
                .fulfillingStoreId(variant.getStoreId())
                .totalRetailCents(retailCents)
                .wholesalePayoutCents(wholesaleCents)
                .arbitrageMarginCents(marginCents)
                .status(TransactionStatus.PENDING_RESERVATION)
                .build();
        transaction = transactionRepository.save(transaction);

        TransactionItem item = TransactionItem.builder()
                .transactionId(transaction.getId())
                .variantId(variantId)
                .quantity(quantity)
                .build();
        transactionItemRepository.save(item);

        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(lockMinutes);

        InventoryLock lock = InventoryLock.builder()
                .transactionId(transaction.getId())
                .variantId(variantId)
                .storeId(variant.getStoreId())
                .lockedQuantity(quantity)
                .expiresAt(expiresAt)
                .status(InventoryLockStatus.HELD)
                .build();
        lock = inventoryLockRepository.save(lock);

        transaction.setStatus(TransactionStatus.RESERVED);
        transactionRepository.save(transaction);

        log.info("Inventory lock created: lock={}, tx={}, variant={}, qty={}, expires={}",
                lock.getId(), transaction.getId(), variantId, quantity, expiresAt);

        return CheckAvailabilityResponse.builder()
                .transactionId(transaction.getId())
                .inventoryLockId(lock.getId())
                .variantId(variantId)
                .lockedQuantity(quantity)
                .retailPriceCents(variant.getRetailPriceCents())
                .wholesalePriceCents(variant.getWholesalePriceCents())
                .expiresAt(expiresAt)
                .status(TransactionStatus.RESERVED.name())
                .lockMinutes(lockMinutes)
                .build();
    }

    public CheckAvailabilityResponse getLockStatus(UUID transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId.toString()));

        List<InventoryLock> locks = inventoryLockRepository.findByTransactionId(transactionId);
        if (locks.isEmpty()) {
            throw new ResourceNotFoundException("InventoryLock for Transaction", transactionId.toString());
        }

        InventoryLock lock = locks.get(0);
        if (lock.getStatus() == InventoryLockStatus.RELEASED_TO_STOCK
                || (lock.getExpiresAt().isBefore(OffsetDateTime.now())
                && lock.getStatus() == InventoryLockStatus.HELD)) {
            throw new ReservationExpiredException(
                    "Reservation window has expired",
                    transactionId, lock.getId(), lock.getExpiresAt());
        }

        ProductVariant variant = variantRepository.findById(lock.getVariantId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", lock.getVariantId().toString()));

        return CheckAvailabilityResponse.builder()
                .transactionId(tx.getId())
                .inventoryLockId(lock.getId())
                .variantId(lock.getVariantId())
                .lockedQuantity(lock.getLockedQuantity())
                .retailPriceCents(variant.getRetailPriceCents())
                .wholesalePriceCents(variant.getWholesalePriceCents())
                .expiresAt(lock.getExpiresAt())
                .status(tx.getStatus().name())
                .lockMinutes(lockMinutes)
                .build();
    }

    public List<ProductVariant> searchNearbyVariants(
            UUID productId, UUID excludeStoreId, Map<String, Object> variantAttributes, int minQuantity) {
        String attributesJsonb = "{}";
        if (variantAttributes != null && !variantAttributes.isEmpty()) {
            try {
                attributesJsonb = objectMapper.writeValueAsString(variantAttributes);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize variant attributes filter, using empty", e);
            }
        }
        return variantRepository.findAvailableVariantsWithMatchingAttributes(
                productId, excludeStoreId, attributesJsonb,
                VariantStatus.ACTIVE.name(), minQuantity
        );
    }
}
