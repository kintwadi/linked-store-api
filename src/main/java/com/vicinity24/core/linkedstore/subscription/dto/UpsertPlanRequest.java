package com.vicinity24.core.linkedstore.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UpsertPlanRequest {
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
    private String badgesCsv;
    private Boolean contactSalesEnabled;
    private String contactSalesEmail;
    private String contactSalesUrl;
    private Boolean isActive;
    private List<UpsertPlanFeatureRequest> features;
}
