package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.entity.SubscriptionInterval;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlanFeature;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionPlanSeeder {

    private final SubscriptionPlanRepository planRepository;
    private final StripeConfig stripeConfig;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedPlans() {
        ensureLegacyPlusPlan();
        ensureProPlan();
        ensureCustomPlan();
    }

    private void ensureLegacyPlusPlan() {
        planRepository.findByPlanCode("PLUS").ifPresentOrElse(existing -> {
            boolean dirty = false;
            if (!existing.getDisplayName().equals("Plus")) { existing.setDisplayName("Plus"); dirty = true; }
            if (!"Standard omnichannel subscription for single-store retailers.".equals(existing.getDescription())) {
                existing.setDescription("Standard omnichannel subscription for single-store retailers."); dirty = true;
            }
            if (!existing.getPriceCents().equals(4900)) { existing.setPriceCents(4900); dirty = true; }
            if (existing.getIntervalUnit() != SubscriptionInterval.MONTH) { existing.setIntervalUnit(SubscriptionInterval.MONTH); dirty = true; }
            if (!existing.getIntervalCount().equals(1)) { existing.setIntervalCount(1); dirty = true; }
            if (!"usd".equals(existing.getCurrency())) { existing.setCurrency("usd"); dirty = true; }
            String spId = stripeConfig.getSubscriptionPlusPriceId();
            if (spId != null && !spId.isBlank() && (existing.getStripePriceId() == null || existing.getStripePriceId().isBlank())) {
                existing.setStripePriceId(spId); dirty = true;
            }
            if (!existing.getTrialDays().equals(3)) { existing.setTrialDays(3); dirty = true; }
            if (existing.getMaxStores() == null || !existing.getMaxStores().equals(1)) { existing.setMaxStores(1); dirty = true; }
            if (existing.getMaxRunners() == null || !existing.getMaxRunners().equals(10)) { existing.setMaxRunners(10); dirty = true; }
            if (existing.getMaxProducts() == null || !existing.getMaxProducts().equals(500)) { existing.setMaxProducts(500); dirty = true; }
            if (dirty) planRepository.save(existing);
            log.info("SubscriptionPlan: legacy PLUS preserved code=PLUS price=4900c stripe={}",
                    existing.getStripePriceId() == null ? "" : existing.getStripePriceId());
        }, () -> log.info("SubscriptionPlan: legacy PLUS not seeded (not required)."));
    }

    private void ensureProPlan() {
        String stripePriceId = stripeConfig.getSubscriptionProPriceId();
        int monthlyCents = 2900;
        int annualCents = 27600;
        int annualDiscount = 21;
        planRepository.findByPlanCode("PRO").ifPresentOrElse(existing -> {
            boolean dirty = false;
            if (!existing.getDisplayName().equals("Pro Plan")) { existing.setDisplayName("Pro Plan"); dirty = true; }
            String desc = "Everything growing stores need to list, broker, and fulfill inventory across the network.";
            if (!desc.equals(existing.getDescription())) { existing.setDescription(desc); dirty = true; }
            if (existing.getPriceCents() == null || !existing.getPriceCents().equals(monthlyCents)) { existing.setPriceCents(monthlyCents); dirty = true; }
            if (existing.getAnnualPriceCents() == null || !existing.getAnnualPriceCents().equals(annualCents)) { existing.setAnnualPriceCents(annualCents); dirty = true; }
            if (existing.getAnnualDiscountPercent() == null || !existing.getAnnualDiscountPercent().equals(annualDiscount)) { existing.setAnnualDiscountPercent(annualDiscount); dirty = true; }
            if (existing.getIntervalUnit() != SubscriptionInterval.MONTH) { existing.setIntervalUnit(SubscriptionInterval.MONTH); dirty = true; }
            if (existing.getIntervalCount() == null || !existing.getIntervalCount().equals(1)) { existing.setIntervalCount(1); dirty = true; }
            if (!"usd".equals(existing.getCurrency())) { existing.setCurrency("usd"); dirty = true; }
            if (stripePriceId != null && !stripePriceId.isBlank()
                    && (existing.getStripePriceId() == null || existing.getStripePriceId().isBlank())) {
                existing.setStripePriceId(stripePriceId); dirty = true;
            }
            if (existing.getTrialDays() == null || !existing.getTrialDays().equals(30)) { existing.setTrialDays(30); dirty = true; }
            if (existing.getIsActive() == null || !existing.getIsActive()) { existing.setIsActive(true); dirty = true; }
            String blMonthly = "/month";
            if (!blMonthly.equals(existing.getBillingLabelMonthly())) { existing.setBillingLabelMonthly(blMonthly); dirty = true; }
            String blAnnual = "$276.0/yr";
            if (!blAnnual.equals(existing.getBillingLabelAnnual())) { existing.setBillingLabelAnnual(blAnnual); dirty = true; }
            if (existing.getMaxConnectedStores() == null || !existing.getMaxConnectedStores().equals(Integer.MAX_VALUE)) {
                existing.setMaxConnectedStores(Integer.MAX_VALUE); dirty = true;
            }
            if (existing.getMonthlyOrderLimit() == null || !existing.getMonthlyOrderLimit().equals(100)) {
                existing.setMonthlyOrderLimit(100); dirty = true;
            }
            String[] badges = {"Recommended"};
            if (existing.getBadges() == null || existing.getBadges().length != 1 || !badges[0].equals(existing.getBadges()[0])) {
                existing.setBadges(badges); dirty = true;
            }
            if (existing.getContactSalesEnabled() == null || existing.getContactSalesEnabled()) {
                existing.setContactSalesEnabled(false); existing.setContactSalesEmail(null); existing.setContactSalesUrl(null); dirty = true;
            }
            if (existing.getSortOrder() == null || !existing.getSortOrder().equals(0)) { existing.setSortOrder(0); dirty = true; }
            dirty = ensureProFeatures(existing) || dirty;
            if (dirty) planRepository.save(existing);
            log.info("SubscriptionPlan: upserted PRO price={}c annual={}c trial=30d stripe={}",
                    monthlyCents, annualCents,
                    existing.getStripePriceId() == null ? "" : existing.getStripePriceId());
        }, () -> {
            SubscriptionPlan plan = SubscriptionPlan.builder()
                    .planCode("PRO")
                    .displayName("Pro Plan")
                    .description("Everything growing stores need to list, broker, and fulfill inventory across the network.")
                    .priceCents(monthlyCents)
                    .annualPriceCents(annualCents)
                    .annualDiscountPercent(annualDiscount)
                    .intervalUnit(SubscriptionInterval.MONTH)
                    .intervalCount(1)
                    .currency("usd")
                    .stripePriceId((stripePriceId == null || stripePriceId.isBlank()) ? null : stripePriceId)
                    .trialDays(30)
                    .isActive(true)
                    .billingLabelMonthly("/month")
                    .billingLabelAnnual("$276.0/yr")
                    .maxConnectedStores(Integer.MAX_VALUE)
                    .monthlyOrderLimit(100)
                    .badges(new String[]{"Recommended"})
                    .contactSalesEnabled(false)
                    .sortOrder(0)
                    .features(new ArrayList<>())
                    .build();
            seedProFeatureList(plan);
            planRepository.save(plan);
            log.info("SubscriptionPlan: seeded PRO price={}c annual={}c stripe={}", monthlyCents, annualCents,
                    plan.getStripePriceId() == null ? "" : plan.getStripePriceId());
        });
    }

    private void ensureCustomPlan() {
        planRepository.findByPlanCode("CUSTOM").ifPresentOrElse(existing -> {
            boolean dirty = false;
            if (!existing.getDisplayName().equals("Custom Plan")) { existing.setDisplayName("Custom Plan"); dirty = true; }
            String desc = "Tailored volume pricing, dedicated support, and enterprise-grade controls for multi-store operators.";
            if (!desc.equals(existing.getDescription())) { existing.setDescription(desc); dirty = true; }
            if (existing.getPriceCents() != null) { existing.setPriceCents(null); dirty = true; }
            if (existing.getAnnualPriceCents() != null) { existing.setAnnualPriceCents(null); dirty = true; }
            if (existing.getAnnualDiscountPercent() != null) { existing.setAnnualDiscountPercent(null); dirty = true; }
            if (existing.getIntervalUnit() != null) { existing.setIntervalUnit(null); dirty = true; }
            if (existing.getIntervalCount() != null) { existing.setIntervalCount(null); dirty = true; }
            if (existing.getCurrency() != null && !existing.getCurrency().isBlank() && !"usd".equals(existing.getCurrency())) {
                existing.setCurrency("usd"); dirty = true;
            }
            if (existing.getStripePriceId() != null && !existing.getStripePriceId().isBlank()) {
                existing.setStripePriceId(null); dirty = true;
            }
            if (existing.getTrialDays() == null || !existing.getTrialDays().equals(0)) { existing.setTrialDays(0); dirty = true; }
            if (existing.getIsActive() == null || !existing.getIsActive()) { existing.setIsActive(true); dirty = true; }
            if (!"Enterprise".equals(existing.getBillingLabelMonthly())) { existing.setBillingLabelMonthly("Enterprise"); dirty = true; }
            if (!"Custom".equals(existing.getBillingLabelAnnual())) { existing.setBillingLabelAnnual("Custom"); dirty = true; }
            if (existing.getMaxConnectedStores() == null || !existing.getMaxConnectedStores().equals(Integer.MAX_VALUE)) {
                existing.setMaxConnectedStores(Integer.MAX_VALUE); dirty = true;
            }
            if (existing.getMonthlyOrderLimit() == null || !existing.getMonthlyOrderLimit().equals(Integer.MAX_VALUE)) {
                existing.setMonthlyOrderLimit(Integer.MAX_VALUE); dirty = true;
            }
            String[] badges = {"Enterprise"};
            if (existing.getBadges() == null || existing.getBadges().length != 1 || !badges[0].equals(existing.getBadges()[0])) {
                existing.setBadges(badges); dirty = true;
            }
            if (existing.getContactSalesEnabled() == null || !existing.getContactSalesEnabled()) {
                existing.setContactSalesEnabled(true); dirty = true;
            }
            String csEmail = "info@vicinity24.com";
            String csUrl = "/contact-sales";
            if (!csEmail.equals(existing.getContactSalesEmail())) { existing.setContactSalesEmail(csEmail); dirty = true; }
            if (!csUrl.equals(existing.getContactSalesUrl())) { existing.setContactSalesUrl(csUrl); dirty = true; }
            if (existing.getSortOrder() == null || !existing.getSortOrder().equals(1)) { existing.setSortOrder(1); dirty = true; }
            dirty = ensureCustomFeatures(existing) || dirty;
            if (dirty) planRepository.save(existing);
            log.info("SubscriptionPlan: upserted CUSTOM contact-sales email={} url={}", csEmail, csUrl);
        }, () -> {
            SubscriptionPlan plan = SubscriptionPlan.builder()
                    .planCode("CUSTOM")
                    .displayName("Custom Plan")
                    .description("Tailored volume pricing, dedicated support, and enterprise-grade controls for multi-store operators.")
                    .trialDays(0)
                    .isActive(true)
                    .billingLabelMonthly("Enterprise")
                    .billingLabelAnnual("Custom")
                    .maxConnectedStores(Integer.MAX_VALUE)
                    .monthlyOrderLimit(Integer.MAX_VALUE)
                    .badges(new String[]{"Enterprise"})
                    .contactSalesEnabled(true)
                    .contactSalesEmail("info@vicinity24.com")
                    .contactSalesUrl("/contact-sales")
                    .sortOrder(1)
                    .features(new ArrayList<>())
                    .build();
            seedCustomFeatureList(plan);
            planRepository.save(plan);
            log.info("SubscriptionPlan: seeded CUSTOM contact-sales email={}", plan.getContactSalesEmail());
        });
    }

    private boolean ensureProFeatures(SubscriptionPlan plan) {
        if (plan.getFeatures() != null && plan.getFeatures().size() == 5) {
            List<String> expected = List.of(
                    "30-day free trial",
                    "Unlimited connected stores",
                    "Up to 100 monthly orders",
                    "Standard API & webhooks",
                    "Email support"
            );
            boolean ok = true;
            for (int i = 0; i < 5; i++) {
                SubscriptionPlanFeature f = plan.getFeatures().get(i);
                if (!expected.get(i).equals(f.getLabel())
                        || !Boolean.TRUE.equals(f.getIncluded())
                        || (i == 0) != Boolean.TRUE.equals(f.getHighlight())
                        || !f.getDisplayOrder().equals(i)) {

                    ok = false; break;
                }
            }
            if (ok) return false;
        }
        plan.getFeatures().clear();
        seedProFeatureList(plan);
        return true;
    }

    private boolean ensureCustomFeatures(SubscriptionPlan plan) {
        if (plan.getFeatures() != null && plan.getFeatures().size() == 5) {
            List<String> expected = List.of(
                    "Unlimited orders",
                    "Dedicated success manager",
                    "Custom integrations",
                    "Custom SLA",
                    "SAML SSO"
            );
            boolean ok = true;
            for (int i = 0; i < 5; i++) {
                SubscriptionPlanFeature f = plan.getFeatures().get(i);
                if (!expected.get(i).equals(f.getLabel())
                        || !Boolean.TRUE.equals(f.getIncluded())
                        || (i == 0) != Boolean.TRUE.equals(f.getHighlight())
                        || !f.getDisplayOrder().equals(i)) {

                    ok = false; break;
                }
            }
            if (ok) return false;
        }
        plan.getFeatures().clear();
        seedCustomFeatureList(plan);
        return true;
    }

    private void seedProFeatureList(SubscriptionPlan plan) {
        addFeature(plan, "30-day free trial", true, true, 0);
        addFeature(plan, "Unlimited connected stores", true, false, 1);
        addFeature(plan, "Up to 100 monthly orders", true, false, 2);
        addFeature(plan, "Standard API & webhooks", true, false, 3);
        addFeature(plan, "Email support", true, false, 4);
    }

    private void seedCustomFeatureList(SubscriptionPlan plan) {
        addFeature(plan, "Unlimited orders", true, true, 0);
        addFeature(plan, "Dedicated success manager", true, false, 1);
        addFeature(plan, "Custom integrations", true, false, 2);
        addFeature(plan, "Custom SLA", true, false, 3);
        addFeature(plan, "SAML SSO", true, false, 4);
    }

    private static void addFeature(SubscriptionPlan plan, String label, boolean included, boolean highlight, int order) {
        SubscriptionPlanFeature f = SubscriptionPlanFeature.builder()
                .plan(plan)
                .label(label)
                .included(included)
                .highlight(highlight)
                .displayOrder(order)
                .build();
        plan.getFeatures().add(f);
    }
}
