package com.vicinity24.core.linkedstore.api.refund.entity;

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
@Table(name = "refunds", indexes = {
        @Index(name = "idx_refunds_transaction_id", columnList = "transaction_id")
})
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, columnDefinition = "uuid")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "total_refunded_cents", nullable = false)
    private Integer totalRefundedCents;

    @Column(name = "reversed_fulfiller_cents")
    private Integer reversedFulfillerCents;

    @Column(name = "reversed_originator_cents")
    private Integer reversedOriginatorCents;

    @Column(name = "currency", length = 3)
    @Builder.Default
    private String currency = "usd";

    @Column(name = "stripe_refund_id", length = 255)
    private String stripeRefundId;

    @Column(name = "stripe_error", columnDefinition = "text")
    private String stripeError;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_by_user_id", length = 255)
    private String createdByUserId;

    @Column(name = "created_by_store_id", length = 255)
    private String createdByStoreId;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
