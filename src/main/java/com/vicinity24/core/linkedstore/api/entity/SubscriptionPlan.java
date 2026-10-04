package com.vicinity24.core.linkedstore.api.entity;

import com.vicinity24.core.linkedstore.api.converter.StringArrayCsvConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    @Column(name = "price_cents")
    private Integer priceCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_unit", length = 20)
    @Builder.Default
    private SubscriptionInterval intervalUnit = SubscriptionInterval.MONTH;

    @Column(name = "interval_count")
    @Builder.Default
    private Integer intervalCount = 1;

    @Column(name = "currency", length = 10)
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

    @Column(name = "annual_price_cents")
    private Integer annualPriceCents;

    @Column(name = "annual_discount_percent")
    private Integer annualDiscountPercent;

    @Column(name = "billing_label_monthly", length = 120)
    private String billingLabelMonthly;

    @Column(name = "billing_label_annual", length = 160)
    private String billingLabelAnnual;

    @Column(name = "max_connected_stores")
    private Integer maxConnectedStores;

    @Column(name = "monthly_order_limit")
    private Integer monthlyOrderLimit;

    @Convert(converter = StringArrayCsvConverter.class)
    @Column(name = "badges", length = 1024)
    private String[] badges;

    @Column(name = "is_contact_sales_enabled")
    @Builder.Default
    private Boolean contactSalesEnabled = false;

    @Column(name = "contact_sales_email", length = 254)
    private String contactSalesEmail;

    @Column(name = "contact_sales_url", length = 512)
    private String contactSalesUrl;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SubscriptionPlanFeature> features = new ArrayList<>();

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
        if (currency == null && priceCents != null) currency = "usd";
        if (trialDays == null) trialDays = 0;
        if (contactSalesEnabled == null) contactSalesEnabled = false;
        if (sortOrder == null) sortOrder = 0;
        if (features == null) features = new ArrayList<>();
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
