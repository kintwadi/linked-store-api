package com.vicinity24.core.linkedstore.api.entity;

import jakarta.persistence.*;
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
@Table(name = "qr_tokens",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"secure_token"}),
                @UniqueConstraint(columnNames = {"fallback_code"})
        })
public class QrToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", insertable = false, updatable = false)
    private Transaction transaction;

    @Column(name = "runner_id", nullable = false)
    private UUID runnerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "runner_id", insertable = false, updatable = false)
    private StoreUser runner;

    @Column(name = "secure_token", nullable = false, unique = true, length = 255)
    private String secureToken;

    @Column(name = "fallback_code", nullable = false, unique = true, length = 16)
    private String fallbackCode;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "scanned_at")
    private OffsetDateTime scannedAt;
}
