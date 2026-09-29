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
public class PricingPlanResponse {

    private String tier;
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
    private String[] badges;
    private List<FeatureItem> features;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FeatureItem {

        private String label;
        private boolean included;
        private boolean highlight;
    }
}
