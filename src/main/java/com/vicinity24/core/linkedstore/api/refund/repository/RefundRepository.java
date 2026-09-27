package com.vicinity24.core.linkedstore.api.refund.repository;

import com.vicinity24.core.linkedstore.api.refund.entity.Refund;
import com.vicinity24.core.linkedstore.api.refund.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID>, JpaSpecificationExecutor<Refund> {

    Optional<Refund> findTopByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    List<Refund> findAllByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    Set<UUID> findDistinctTransactionIdByStatusInAndTransactionIdIn(
            Set<RefundStatus> statuses,
            Collection<UUID> transactionIds);

    @Query("""
        SELECT DISTINCT r.transactionId
        FROM Refund r
        WHERE r.status = 'COMPLETED'
          AND r.transactionId IN (
              SELECT t.id
              FROM com.vicinity24.core.linkedstore.api.entity.Transaction t
              WHERE t.originatingStoreId = :storeId OR t.fulfillingStoreId = :storeId
          )
    """)
    Set<UUID> findCompletedRefundedTxIdsForStore(@Param("storeId") UUID storeId);
}
