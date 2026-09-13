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
@Table(name = "store_users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id")
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", insertable = false, updatable = false)
    private Store store;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    @Builder.Default
    private UserRole role = UserRole.CLERK;

    @Column(name = "phone_number", length = 50)
    @Builder.Default
    private String phoneNumber = "";

    @Column(name = "email", unique = true, length = 255)
    private String email;

    @Column(name = "password_salt", length = 128)
    private String passwordSalt;

    @Column(name = "password_hash", length = 512)
    private String passwordHash;

    @Column(name = "refresh_token_hash", length = 512)
    private String refreshTokenHash;

    @Column(name = "is_global_admin", nullable = false)
    @Builder.Default
    private Boolean globalAdmin = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = UserStatus.ACTIVE;
        if (globalAdmin == null) globalAdmin = false;
        if (role == null) role = UserRole.CLERK;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank()
                && passwordSalt != null && !passwordSalt.isBlank();
    }

    public boolean isGlobalAdminEffective() {
        return Boolean.TRUE.equals(globalAdmin) || UserRole.GLOBAL_ADMIN.equals(role);
    }

    public boolean isStoreAdminEffective() {
        return isGlobalAdminEffective()
                || UserRole.STORE_ADMIN.equals(role)
                || UserRole.OWNER.equals(role);
    }
}
