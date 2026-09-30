package com.vicinity24.core.linkedstore.api.returns.repository;

import com.vicinity24.core.linkedstore.api.returns.entity.InspectionStatus;
import com.vicinity24.core.linkedstore.api.returns.entity.ReturnedInspectionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReturnedInspectionRecordRepository extends JpaRepository<ReturnedInspectionRecord, UUID> {

    Optional<ReturnedInspectionRecord> findByRefundId(UUID refundId);

    Page<ReturnedInspectionRecord> findByFulfillingStoreIdOrderByCreatedAtDesc(UUID storeId, Pageable page);

    @Query("SELECT r FROM ReturnedInspectionRecord r WHERE r.originatingStoreId = ?1 OR r.fulfillingStoreId = ?1 ORDER BY r.createdAt DESC")
    Page<ReturnedInspectionRecord> findByEitherStoreId(UUID storeId, Pageable pageable);

    Page<ReturnedInspectionRecord> findAllByOrderByCreatedAtDesc(Pageable page);

    @Query("SELECT r.status, COUNT(r) FROM ReturnedInspectionRecord r GROUP BY r.status")
    List<Object[]> findGlobalStatusCounts();

    @Query("SELECT r.status, COUNT(r) FROM ReturnedInspectionRecord r WHERE r.fulfillingStoreId = :storeId GROUP BY r.status")
    List<Object[]> findStatusCountsForFulfillingStore(@Param("storeId") UUID storeId);
}