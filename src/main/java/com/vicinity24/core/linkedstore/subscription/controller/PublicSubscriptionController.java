package com.vicinity24.core.linkedstore.subscription.controller;

import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlanFeature;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionPlanRepository;
import com.vicinity24.core.linkedstore.subscription.PlanTier;
import com.vicinity24.core.linkedstore.subscription.SubscriptionTierSettings;
import com.vicinity24.core.linkedstore.subscription.dto.PricingPlanResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/subscription/v1")
@CrossOrigin(origins = "*", maxAge = 3600)
@RequiredArgsConstructor
public class PublicSubscriptionController {

    private final SubscriptionTierSettings tierSettings;
    private final SubscriptionPlanRepository planRepository;

    @GetMapping("/plans")
    public List<PricingPlanResponse> listPlans() {
        List<PricingPlanResponse> plans = new ArrayList<>();
        plans.add(buildProPlan());
        plans.add(buildCustomPlan());
        return plans;
    }

    private PricingPlanResponse buildProPlan() {
        Optional<SubscriptionPlan> dbOpt = planRepository.findByPlanCode("PRO");
        if (dbOpt.isPresent() && Boolean.TRUE.equals(dbOpt.get().getIsActive())) {
            return fromDbPlan(dbOpt.get(), PlanTier.PRO);
        }
        SubscriptionTierSettings.TierProSettings pro = tierSettings.getPro();
        List<PricingPlanResponse.FeatureItem> features = List.of(
                feature("30-day free trial", true, true),
                feature("Unlimited connected stores", true, false),
                feature("Up to " + pro.getMonthlyOrderLimit() + " monthly orders", true, false),
                feature("Standard API & webhooks", true, false),
                feature("Email support", true, false)
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
        Optional<SubscriptionPlan> dbOpt = planRepository.findByPlanCode("CUSTOM");
        if (dbOpt.isPresent() && Boolean.TRUE.equals(dbOpt.get().getIsActive())) {
            return fromDbPlan(dbOpt.get(), PlanTier.CUSTOM);
        }
        SubscriptionTierSettings.TierCustomSettings custom = tierSettings.getCustom();
        List<PricingPlanResponse.FeatureItem> features = List.of(
                feature("Unlimited orders", true, true),
                feature("Dedicated success manager", true, false),
                feature("Custom integrations", true, false),
                feature("Custom SLA", true, false),
                feature("SAML SSO", true, false)
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

    private PricingPlanResponse fromDbPlan(SubscriptionPlan plan, PlanTier tier) {
        List<SubscriptionPlanFeature> fs = plan.getFeatures() == null ? List.of() : plan.getFeatures();
        List<PricingPlanResponse.FeatureItem> features = fs.stream()
                .sorted(Comparator.comparingInt(f -> f.getDisplayOrder() == null ? 0 : f.getDisplayOrder()))
                .map(f -> feature(f.getLabel(),
                        f.getIncluded() == null ? Boolean.TRUE : f.getIncluded(),
                        f.getHighlight() == null ? Boolean.FALSE : f.getHighlight()))
                .collect(Collectors.toList());
        String billingMonthly = plan.getBillingLabelMonthly();
        String billingAnnual = plan.getBillingLabelAnnual();
        if (tier == PlanTier.PRO) {
            Integer monthly = plan.getPriceCents();
            Integer annual = plan.getAnnualPriceCents();
            if (billingMonthly == null && monthly != null) {
                billingMonthly = "$" + (monthly / 100.0) + "/mo";
            }
            if (billingAnnual == null && annual != null) {
                billingAnnual = "$" + String.format("%.2f", annual / 1200.0) + "/mo, billed annually";
            }
        } else if (tier == PlanTier.CUSTOM && Boolean.TRUE.equals(plan.getContactSalesEnabled())) {
            if (billingMonthly == null) billingMonthly = "Contact sales";
            if (billingAnnual == null) billingAnnual = "Custom pricing";
        }
        return PricingPlanResponse.builder()
                .tier(tier.name())
                .displayName(plan.getDisplayName())
                .description(plan.getDescription())
                .monthlyPriceCents(plan.getPriceCents())
                .annualPriceCents(plan.getAnnualPriceCents())
                .annualDiscountPercent(plan.getAnnualDiscountPercent())
                .billingLabelMonthly(billingMonthly)
                .billingLabelAnnual(billingAnnual)
                .currency(plan.getCurrency())
                .trialDays(plan.getTrialDays())
                .monthlyOrderLimit(normalizeUnlimitedQuota(plan.getMonthlyOrderLimit()))
                .badges(plan.getBadges() == null ? new String[0] : plan.getBadges())
                .features(features)
                .build();
    }

    private static Integer normalizeUnlimitedQuota(Integer v) {
        if (v == null) return null;
        if (v < 0 || v.equals(Integer.MAX_VALUE)) return null;
        return v;
    }

    private static PricingPlanResponse.FeatureItem feature(String label, boolean included, boolean highlight) {
        return PricingPlanResponse.FeatureItem.builder()
                .label(label)
                .included(included)
                .highlight(highlight)
                .build();
    }
}
