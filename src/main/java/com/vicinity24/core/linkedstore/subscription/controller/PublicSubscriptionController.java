package com.vicinity24.core.linkedstore.subscription.controller;

import com.vicinity24.core.linkedstore.subscription.PlanTier;
import com.vicinity24.core.linkedstore.subscription.SubscriptionTierSettings;
import com.vicinity24.core.linkedstore.subscription.dto.PricingPlanResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/subscription/v1")
@CrossOrigin(origins = "*", maxAge = 3600)
@RequiredArgsConstructor
public class PublicSubscriptionController {

    private final SubscriptionTierSettings tierSettings;

    /**
     * Lists the two public pricing plans (PRO and CUSTOM).
     *
     * Badge placement convention: the PRO tier is tagged "Recommended" because it is the
     * default self-serve plan targeted at most SMB stores. The CUSTOM tier is tagged
     * "Enterprise" to surface it as an upsell path for stores hitting PRO quotas or
     * needing bespoke commercial terms.
     */
    @GetMapping("/plans")
    public List<PricingPlanResponse> listPlans() {
        List<PricingPlanResponse> plans = new ArrayList<>();
        plans.add(buildProPlan());
        plans.add(buildCustomPlan());
        return plans;
    }

    private PricingPlanResponse buildProPlan() {
        SubscriptionTierSettings.TierProSettings pro = tierSettings.getPro();

        List<PricingPlanResponse.FeatureItem> features = List.of(
                PricingPlanResponse.FeatureItem.builder()
                        .label("30-day free trial")
                        .included(true)
                        .highlight(true)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Unlimited connected stores")
                        .included(true)
                        .highlight(false)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Up to " + pro.getMonthlyOrderLimit() + " monthly orders")
                        .included(true)
                        .highlight(false)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Standard API & webhooks")
                        .included(true)
                        .highlight(false)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Email support")
                        .included(true)
                        .highlight(false)
                        .build()
        );

        return PricingPlanResponse.builder()
                .tier(PlanTier.PRO.name())
                .displayName(PlanTier.PRO.getDisplayName())
                .description("Everything growing stores need to list, broker, and fulfill inventory across the network.")
                .monthlyPriceCents(pro.getMonthlyPriceCents())
                .annualPriceCents(pro.getAnnualPriceCents())
                .annualDiscountPercent(20)
                .billingLabelMonthly("$" + (pro.getMonthlyPriceCents() / 100.0) + "/mo")
                .billingLabelAnnual("$" + String.format("%.2f", pro.getAnnualPriceCents() / 1200.0) + "/mo, billed annually")
                .currency(pro.getCurrency())
                .trialDays(pro.getTrialDays())
                .monthlyOrderLimit(pro.getMonthlyOrderLimit())
                .badges(new String[]{"Recommended"})
                .features(features)
                .build();
    }

    private PricingPlanResponse buildCustomPlan() {
        SubscriptionTierSettings.TierCustomSettings custom = tierSettings.getCustom();

        List<PricingPlanResponse.FeatureItem> features = List.of(
                PricingPlanResponse.FeatureItem.builder()
                        .label("Unlimited orders")
                        .included(true)
                        .highlight(true)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Dedicated success manager")
                        .included(true)
                        .highlight(false)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Custom integrations")
                        .included(true)
                        .highlight(false)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("Custom SLA")
                        .included(true)
                        .highlight(false)
                        .build(),
                PricingPlanResponse.FeatureItem.builder()
                        .label("SAML SSO")
                        .included(true)
                        .highlight(false)
                        .build()
        );

        return PricingPlanResponse.builder()
                .tier(PlanTier.CUSTOM.name())
                .displayName(custom.getDisplayName())
                .description("Tailored volume pricing, dedicated support, and enterprise-grade controls for multi-store operators.")
                .monthlyPriceCents(null)
                .annualPriceCents(null)
                .annualDiscountPercent(null)
                .billingLabelMonthly("Contact sales")
                .billingLabelAnnual("Custom pricing")
                .currency(null)
                .trialDays(null)
                .monthlyOrderLimit(null)
                .badges(new String[]{"Enterprise"})
                .features(features)
                .build();
    }
}
