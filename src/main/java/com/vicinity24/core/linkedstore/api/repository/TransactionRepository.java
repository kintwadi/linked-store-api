package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    List<Transaction> findByOriginatingStoreId(UUID originatingStoreId);

    List<Transaction> findByFulfillingStoreId(UUID fulfillingStoreId);

    Optional<Transaction> findByStripePaymentIntentId(String stripePaymentIntentId);

    List<Transaction> findByStatus(TransactionStatus status);

    @Query("""
        SELECT t FROM Transaction t
        WHERE (t.originatingStoreId = :storeId OR t.fulfillingStoreId = :storeId)
        ORDER BY t.createdAt DESC
    """)
    List<Transaction> findAllInvolvingStore(@Param("storeId") UUID storeId);

    @Modifying
    @Query("""
        UPDATE Transaction t
        SET t.status = :newStatus
        WHERE t.id = :transactionId
          AND t.status = :expectedStatus
    """)
    int updateStatusIfCurrentStatusIs(
            @Param("transactionId") UUID transactionId,
            @Param("newStatus") TransactionStatus newStatus,
            @Param("expectedStatus") TransactionStatus expectedStatus
    );
}
