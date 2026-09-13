package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.InventoryLock;
import com.vicinity24.core.linkedstore.api.entity.InventoryLockStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface InventoryLockRepository extends JpaRepository<InventoryLock, UUID> {

    List<InventoryLock> findByTransactionId(UUID transactionId);

    List<InventoryLock> findByVariantIdAndStatus(UUID variantId, InventoryLockStatus status);

    @Query("""
        SELECT il FROM InventoryLock il
        WHERE il.status = :status
          AND il.expiresAt <= :expiryThreshold
        ORDER BY il.expiresAt ASC
    """)
    List<InventoryLock> findExpiredLocks(
            @Param("status") InventoryLockStatus status,
            @Param("expiryThreshold") OffsetDateTime expiryThreshold
    );

    @Query(value = """
        SELECT * FROM inventory_locks
        WHERE status = :status
          AND expires_at <= :expiryThreshold
        ORDER BY expires_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :batchSize
        """, nativeQuery = true)
    List<InventoryLock> findExpiredLocksForReleaseBatch(
            @Param("status") String status,
            @Param("expiryThreshold") OffsetDateTime expiryThreshold,
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query("""
        UPDATE InventoryLock il
        SET il.status = :newStatus
        WHERE il.id = :lockId
          AND il.status = :expectedStatus
    """)
    int updateStatusIfCurrentStatusIs(
            @Param("lockId") UUID lockId,
            @Param("newStatus") InventoryLockStatus newStatus,
            @Param("expectedStatus") InventoryLockStatus expectedStatus
    );

    @Modifying
    @Query("""
        UPDATE InventoryLock il
        SET il.status = :newStatus
        WHERE il.transactionId = :transactionId
          AND il.status = :expectedStatus
    """)
    int updateStatusByTransactionIdIfCurrentStatusIs(
            @Param("transactionId") UUID transactionId,
            @Param("newStatus") InventoryLockStatus newStatus,
            @Param("expectedStatus") InventoryLockStatus expectedStatus
    );
}
