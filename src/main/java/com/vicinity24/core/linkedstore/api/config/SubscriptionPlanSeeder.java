package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.entity.SubscriptionInterval;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionPlanSeeder {

    private final SubscriptionPlanRepository planRepository;
    private final StripeConfig stripeConfig;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedPlans() {
        ensurePlan("PLUS",
                "Plus",
                "Standard omnichannel subscription for single-store retailers.",
                4900,
                stripeConfig.getSubscriptionPlusPriceId(),
                3, 1, 10, 500);
        ensurePlan("PRO",
                "Pro",
                "Unified multi-store subscription with runner logistics and volume discounts.",
                14900,
                stripeConfig.getSubscriptionProPriceId(),
                14, 5, 5, 5000);
    }

    private void ensurePlan(String code, String name, String description, int priceCents,
                            String stripePriceId, int trialDays,
                            int maxStores, int maxRunners, int maxProducts) {
        planRepository.findByPlanCode(code).ifPresentOrElse(existing -> {
            boolean dirty = false;
            if (!existing.getDisplayName().equals(name)) { existing.setDisplayName(name); dirty = true; }
            if (!existing.getDescription().equals(description)) { existing.setDescription(description); dirty = true; }
            if (stripePriceId != null && !stripePriceId.isBlank()
                    && (existing.getStripePriceId() == null || existing.getStripePriceId().isBlank())) {
                existing.setStripePriceId(stripePriceId);
                dirty = true;
            }
            if (!existing.getPriceCents().equals(priceCents)) { existing.setPriceCents(priceCents); dirty = true; }
            if (!existing.getTrialDays().equals(trialDays)) { existing.setTrialDays(trialDays); dirty = true; }
            if (existing.getMaxStores() == null || !existing.getMaxStores().equals(maxStores)) {
                existing.setMaxStores(maxStores); dirty = true;
            }
            if (existing.getMaxRunners() == null || !existing.getMaxRunners().equals(maxRunners)) {
                existing.setMaxRunners(maxRunners); dirty = true;
            }
            if (existing.getMaxProducts() == null || !existing.getMaxProducts().equals(maxProducts)) {
                existing.setMaxProducts(maxProducts); dirty = true;
            }
            if (dirty) planRepository.save(existing);
            log.info("SubscriptionPlan: upserted code={} price={}c stripe={}", code, priceCents,
                    existing.getStripePriceId() == null ? "" : existing.getStripePriceId());
        }, () -> {
            SubscriptionPlan plan = SubscriptionPlan.builder()
                    .planCode(code)
                    .displayName(name)
                    .description(description)
                    .priceCents(priceCents)
                    .intervalUnit(SubscriptionInterval.MONTH)
                    .intervalCount(1)
                    .currency("usd")
                    .stripePriceId((stripePriceId == null || stripePriceId.isBlank()) ? null : stripePriceId)
                    .trialDays(trialDays)
                    .isActive(true)
                    .maxStores(maxStores)
                    .maxRunners(maxRunners)
                    .maxProducts(maxProducts)
                    .build();
            planRepository.save(plan);
            log.info("SubscriptionPlan: seeded code={} price={}c stripe={}", code, priceCents,
                    plan.getStripePriceId() == null ? "" : plan.getStripePriceId());
        });
    }
}
