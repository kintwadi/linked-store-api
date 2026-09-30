package com.vicinity24.core.linkedstore.api.returns.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "returned_inspection_records", indexes = {
        @Index(name = "idx_rir_refund_id", columnList = "refund_id"),
        @Index(name = "idx_rir_tx_id", columnList = "transaction_id"),
        @Index(name = "idx_rir_variant_id", columnList = "variant_id"),
        @Index(name = "idx_rir_fulfill_store_id", columnList = "fulfilling_store_id"),
        @Index(name = "idx_rir_origin_store_id", columnList = "originating_store_id")
})
public class ReturnedInspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "refund_id", columnDefinition = "uuid")
    private UUID refundId;

    @Column(name = "transaction_id", columnDefinition = "uuid")
    private UUID transactionId;

    @Column(name = "variant_id", columnDefinition = "uuid")
    private UUID variantId;

    @Column(name = "product_id", columnDefinition = "uuid")
    private UUID productId;

    @Column(name = "fulfilling_store_id", columnDefinition = "uuid")
    private UUID fulfillingStoreId;

    @Column(name = "originating_store_id", columnDefinition = "uuid")
    private UUID originatingStoreId;

    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50, columnDefinition = "varchar(50) check (status in ('UNDER_INSPECTION','PASSED_INSPECTION','REJECTED','RESTOCKED'))")
    @Builder.Default
    private InspectionStatus status = InspectionStatus.UNDER_INSPECTION;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "inspected_by_user_id", length = 255)
    private String inspectedByUserId;

    @Column(name = "inspected_by_store_id", length = 255)
    private String inspectedByStoreId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "inspected_at")
    private OffsetDateTime inspectedAt;

    @Version
    @Column(name = "version")
    private Integer version;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
