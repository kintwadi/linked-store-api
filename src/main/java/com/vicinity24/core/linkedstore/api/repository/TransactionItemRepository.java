package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.TransactionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionItemRepository extends JpaRepository<TransactionItem, UUID> {

    List<TransactionItem> findByTransactionId(UUID transactionId);

    @Query("""
        SELECT ti FROM TransactionItem ti
        JOIN FETCH ti.variant
        WHERE ti.transactionId = :transactionId
    """)
    List<TransactionItem> findByTransactionIdWithVariants(@Param("transactionId") UUID transactionId);
}
