package com.vicinity24.core.linkedstore.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "originating_store_id", nullable = false)
    private UUID originatingStoreId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "originating_store_id", insertable = false, updatable = false)
    private Store originatingStore;

    @Column(name = "fulfilling_store_id", nullable = false)
    private UUID fulfillingStoreId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfilling_store_id", insertable = false, updatable = false)
    private Store fulfillingStore;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "total_retail_cents", nullable = false)
    private Integer totalRetailCents;

    @Column(name = "wholesale_payout_cents", nullable = false)
    private Integer wholesalePayoutCents;

    @Column(name = "arbitrage_margin_cents", nullable = false)
    private Integer arbitrageMarginCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
