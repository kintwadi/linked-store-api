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
@Table(name = "subscription_plans", uniqueConstraints = {
        @UniqueConstraint(name = "uk_subscription_plans_code", columnNames = "plan_code")
})
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_code", nullable = false, length = 50)
    private String planCode;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "price_cents", nullable = false)
    private Integer priceCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_unit", nullable = false, length = 20)
    @Builder.Default
    private SubscriptionInterval intervalUnit = SubscriptionInterval.MONTH;

    @Column(name = "interval_count", nullable = false)
    @Builder.Default
    private Integer intervalCount = 1;

    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "usd";

    @Column(name = "stripe_price_id", length = 255)
    private String stripePriceId;

    @Column(name = "trial_days")
    @Builder.Default
    private Integer trialDays = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "max_stores")
    private Integer maxStores;

    @Column(name = "max_runners")
    private Integer maxRunners;

    @Column(name = "max_products")
    private Integer maxProducts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (isActive == null) isActive = true;
        if (intervalCount == null) intervalCount = 1;
        if (currency == null) currency = "usd";
        if (trialDays == null) trialDays = 0;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
