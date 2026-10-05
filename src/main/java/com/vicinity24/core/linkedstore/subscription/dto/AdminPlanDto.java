package com.vicinity24.core.linkedstore.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdminPlanDto {
    private UUID id;
    private String planCode;
    private String displayName;
    private String description;
    private Integer monthlyPriceCents;
    private Integer annualPriceCents;
    private Integer annualDiscountPercent;
    private String billingLabelMonthly;
    private String billingLabelAnnual;
    private String currency;
    private Integer trialDays;
    private Integer monthlyOrderLimit;
    private Integer maxConnectedStores;
    private Integer sortOrder;
    private String[] badges;
    private Boolean contactSalesEnabled;
    private String contactSalesEmail;
    private String contactSalesUrl;
    private String stripePriceIdLegacy;
    private Boolean isActive;
    private List<AdminPlanFeatureDto> features;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
