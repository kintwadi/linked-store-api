package com.vicinity24.core.linkedstore.api.scheduler;

import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
import com.vicinity24.core.linkedstore.api.entity.InventoryLock;
import com.vicinity24.core.linkedstore.api.entity.InventoryLockStatus;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.InventoryLockRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryLockExpiryScheduler {

    private final InventoryLockRepository inventoryLockRepository;
    private final ProductVariantRepository variantRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventBroadcaster eventBroadcaster;

    @Value("${inventory.expiry-scheduler-seconds:30}")
    private int schedulerIntervalSeconds;

    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedRateString = "${inventory.expiry-scheduler-seconds:30}000")
    @Transactional
    public void releaseExpiredInventoryLocks() {
        OffsetDateTime now = OffsetDateTime.now();
        log.trace("Running inventory lock expiry scheduler at {}", now);

        int totalReleased = 0;
        int totalRestored = 0;
        int totalTxExpired = 0;

        while (true) {
            List<InventoryLock> batch = inventoryLockRepository.findExpiredLocksForReleaseBatch(
                    InventoryLockStatus.HELD.name(),
                    now,
                    BATCH_SIZE
            );

            if (batch.isEmpty()) {
                break;
            }

            AtomicInteger batchReleased = new AtomicInteger(0);
            AtomicInteger batchRestored = new AtomicInteger(0);
            AtomicInteger batchTxExpired = new AtomicInteger(0);

            batch.forEach(lock -> {
                try {
                    processExpiredLock(lock, batchReleased, batchRestored, batchTxExpired);
                } catch (Exception e) {
                    log.error("Failed to process expired lock: lockId={}, txId={}",
                            lock.getId(), lock.getTransactionId(), e);
                }
            });

            totalReleased += batchReleased.get();
            totalRestored += batchRestored.get();
            totalTxExpired += batchTxExpired.get();

            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalReleased > 0) {
            log.info("Inventory lock expiry sweep complete: released={} locks, restored={} stock, expired={} transactions",
                    totalReleased, totalRestored, totalTxExpired);
        }
    }

    private void processExpiredLock(InventoryLock lock,
                                    AtomicInteger releasedCounter,
                                    AtomicInteger restoredCounter,
                                    AtomicInteger txExpiredCounter) {
        int rows = inventoryLockRepository.updateStatusIfCurrentStatusIs(
                lock.getId(),
                InventoryLockStatus.RELEASED_TO_STOCK,
                InventoryLockStatus.HELD
        );

        if (rows == 0) {
            log.debug("Lock {} already released by concurrent process, skipping", lock.getId());
            return;
        }
        releasedCounter.incrementAndGet();

        int restored = variantRepository.restoreStock(lock.getVariantId(), lock.getLockedQuantity());
        if (restored > 0) {
            restoredCounter.addAndGet(restored);
        } else {
            log.warn("Stock restoration returned 0 for variant={}, qty={}",
                    lock.getVariantId(), lock.getLockedQuantity());
        }

        // Transition eligible hold-states to EXPIRED: PENDING_RESERVATION, RESERVED, READY.
        // Old logic only covered RESERVED; cover all active hold states now.
        int txRows = 0;
        for (TransactionStatus expected : new TransactionStatus[]{
                TransactionStatus.RESERVED,
                TransactionStatus.PENDING_RESERVATION,
                TransactionStatus.READY}) {
            txRows = transactionRepository.updateStatusIfCurrentStatusIs(
                    lock.getTransactionId(),
                    TransactionStatus.EXPIRED,
                    expected
            );
            if (txRows > 0) break;
        }
        if (txRows > 0) {
            txExpiredCounter.incrementAndGet();
            broadcastExpired(lock.getTransactionId());
        }

        log.debug("Expired lock processed: lockId={}, txId={}, variantId={}, qty={}",
                lock.getId(), lock.getTransactionId(), lock.getVariantId(), lock.getLockedQuantity());
    }

    /** Broadcast an EXPIRED event so the admin/store notification list hides the item as no-longer-actionable. */
    private void broadcastExpired(UUID transactionId) {
        try {
            Transaction tx = transactionRepository.findById(transactionId).orElse(null);
            if (tx == null) return;
            ProductVariant variant = null;
            if (tx.getId() != null) {
                List<InventoryLock> locks = inventoryLockRepository.findByTransactionId(transactionId);
                for (InventoryLock l : locks) {
                    if (l.getVariantId() != null) {
                        variant = variantRepository.findById(l.getVariantId()).orElse(null);
                        if (variant != null) break;
                    }
                }
            }
            UUID variantId = variant != null ? variant.getId() : null;
            UUID productId = variant != null && variant.getProductId() != null ? variant.getProductId() : null;
            String productTitle = variant != null && variant.getProduct() != null ? variant.getProduct().getTitle() : null;
            String productImageUrl = variant != null && variant.getProduct() != null ? variant.getProduct().getPrimaryImageUrl() : null;
            String sku = variant != null ? variant.getSku() : null;
            BigDecimal retailPrice = variant != null && variant.getRetailPriceCents() != null
                    ? BigDecimal.valueOf(variant.getRetailPriceCents()).scaleByPowerOfTen(-2)
                    : null;

            TxEvent ev = TxEvent.builder()
                    .type(TxEventType.EXPIRED)
                    .createdAt(OffsetDateTime.now())
                    .transactionId(tx.getId())
                    .storeId(tx.getFulfillingStoreId())
                    .fulfillingStoreId(tx.getFulfillingStoreId())
                    .originatingStoreId(tx.getOriginatingStoreId())
                    .variantId(variantId)
                    .productId(productId)
                    .productTitle(productTitle)
                    .productImageUrl(productImageUrl)
                    .sku(sku)
                    .retailPrice(retailPrice)
                    .currency("USD")
                    .expiresAt(OffsetDateTime.now())
                    .status(TransactionStatus.EXPIRED.name())
                    .message("Customer request expired: item hold timed out and inventory has been returned to shelf.")
                    .build();
            eventBroadcaster.broadcast(ev);
        } catch (Exception ex) {
            log.warn("broadcast EXPIRED failed for txId={}", transactionId, ex);
        }
    }
}
