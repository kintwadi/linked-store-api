package com.vicinity24.core.linkedstore.api.scheduler;

import com.vicinity24.core.linkedstore.api.entity.InventoryLock;
import com.vicinity24.core.linkedstore.api.entity.InventoryLockStatus;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.InventoryLockRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryLockExpiryScheduler {

    private final InventoryLockRepository inventoryLockRepository;
    private final ProductVariantRepository variantRepository;
    private final TransactionRepository transactionRepository;

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

        int txRows = transactionRepository.updateStatusIfCurrentStatusIs(
                lock.getTransactionId(),
                TransactionStatus.EXPIRED,
                TransactionStatus.RESERVED
        );
        if (txRows > 0) {
            txExpiredCounter.incrementAndGet();
        }

        log.debug("Expired lock processed: lockId={}, txId={}, variantId={}, qty={}",
                lock.getId(), lock.getTransactionId(), lock.getVariantId(), lock.getLockedQuantity());
    }
}
