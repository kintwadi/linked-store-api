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
@Table(name = "store_invites")
public class StoreInvite {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REDEEMED = "REDEEMED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "invite_token", unique = true, nullable = false, length = 64)
    private String inviteToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_role", nullable = false, length = 50)
    private UserRole targetRole;

    @Column(name = "prefill_email", length = 255)
    private String prefillEmail;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "redeemed_by")
    private UUID redeemedBy;

    @Column(name = "redeemed_at")
    private OffsetDateTime redeemedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
    }
}
