package com.vicinity24.core.linkedstore.api.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.param.SubscriptionUpdateParams;
import com.vicinity24.core.linkedstore.api.config.BrandProperties;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.exception.*;
import com.vicinity24.core.linkedstore.api.payment.*;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionPlanRepository;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final StoreRepository storeRepository;
    private final PaymentProviderFactory paymentProviderFactory;
    private final StripeConfig stripeConfig;
    private final BrandProperties brandProperties;

    public List<SubscriptionPlan> listPlans() {
        return planRepository.findByIsActiveTrueOrderByPriceCentsAsc();
    }

    public SubscriptionPlan requirePlanByCode(String planCode) {
        return planRepository.findByPlanCode(planCode)
                .filter(SubscriptionPlan::getIsActive)
                .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPlan (code)", planCode));
    }

    public Optional<Subscription> getCurrentStoreSubscription(UUID storeId) {
        return subscriptionRepository.findFirstByStoreIdOrderByCreatedAtDesc(storeId);
    }

    public List<Subscription> listStoreSubscriptions(UUID storeId) {
        storeExists(storeId);
        return subscriptionRepository.findByStoreId(storeId);
    }

    @Transactional
    public PaymentResponse purchaseSubscription(
            UUID storeId, String planCode, String provider,
            String paymentMethodId, String customerEmail, String idempotencyKey) {
        Store store = storeExists(storeId);
        SubscriptionPlan plan = requirePlanByCode(planCode);

        PaymentProvider paymentProvider = paymentProviderFactory.getProvider(provider);
        String description = "%s Subscription (%s) for store %s".formatted(
                brandProperties.getDisplayName(), plan.getPlanCode(), store.getId());
        PaymentRequest request = PaymentRequest.builder()
                .provider(paymentProvider.providerId())
                .paymentMethodId(paymentMethodId)
                .customerEmail(customerEmail)
                .idempotencyKey(idempotencyKey)
                .amountCents(plan.getPriceCents())
                .currency(plan.getCurrency())
                .description(description)
                .metadata(Map.of(
                        "store_id", storeId.toString(),
                        "plan_code", plan.getPlanCode()
                ))
                .build();
        PaymentResponse payment = paymentProvider.capturePayment(request);
        log.info("Subscription payment captured: store={} plan={} pi={}", storeId, planCode, payment.getPaymentIntentId());

        OffsetDateTime now = OffsetDateTime.now();
        int months = plan.getIntervalUnit() == SubscriptionInterval.MONTH ? plan.getIntervalCount() : 12;
        Subscription subscription = Subscription.builder()
                .storeId(storeId)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .provider(paymentProvider.providerId())
                .providerSubscriptionId(payment.getPaymentIntentId())
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plusMonths(months))
                .trialStart(null)
                .trialEnd(null)
                .cancelAtPeriodEnd(false)
                .build();
        subscription = subscriptionRepository.save(subscription);

        store.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        store.setActiveSubscription(subscription);
        storeRepository.save(store);

        return payment;
    }

    @Transactional
    public Subscription activateFreeTrial(UUID storeId, String planCode) {
        Store store = storeExists(storeId);
        SubscriptionPlan plan = requirePlanByCode(planCode);
        if (plan.getTrialDays() == null || plan.getTrialDays() <= 0) {
            throw new ResourceNotFoundException(
                    "SubscriptionPlan has no trial (code)", planCode);
        }

        List<Subscription> prevTrials = subscriptionRepository.findByStoreId(storeId);
        if (prevTrials.stream().anyMatch(s -> s.getTrialStart() != null)) {
            throw new TransactionStateException(
                    "Store already consumed its free trial",
                    storeId, null, null);
        }

        OffsetDateTime now = OffsetDateTime.now();
        Subscription subscription = Subscription.builder()
                .storeId(storeId)
                .plan(plan)
                .status(SubscriptionStatus.TRIALING)
                .provider("MANUAL")
                .trialStart(now)
                .trialEnd(now.plusDays(plan.getTrialDays()))
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plusDays(plan.getTrialDays()))
                .cancelAtPeriodEnd(true)
                .build();
        subscription = subscriptionRepository.save(subscription);

        store.setSubscriptionStatus(SubscriptionStatus.TRIALING);
        store.setActiveSubscription(subscription);
        storeRepository.save(store);

        log.info("Free trial activated: store={} plan={} ends={}", storeId, planCode, subscription.getTrialEnd());
        return subscription;
    }

    @Transactional
    public Subscription cancelAtPeriodEnd(UUID storeId) {
        Store store = storeExists(storeId);

        Subscription current = subscriptionRepository
                .findFirstByStoreIdOrderByCreatedAtDesc(storeId)
                .or(() -> {
                    if (store.getActiveSubscription() != null) {
                        return Optional.of(store.getActiveSubscription());
                    }
                    // Build a bare-bones local subscription row from the Store column state
                    // so idempotent cancellation can still proceed even if webhook missed it.
                    Subscription bare = Subscription.builder()
                            .storeId(storeId)
                            .plan(resolvePlanOrDefault(null))
                            .status(store.getSubscriptionStatus() != null ? store.getSubscriptionStatus() : SubscriptionStatus.ACTIVE)
                            .provider("STRIPE")
                            .build();
                    return Optional.of(subscriptionRepository.save(bare));
                })
                .orElseThrow(() -> new ResourceNotFoundException("Active Subscription for Store", storeId.toString()));

        if (Boolean.TRUE.equals(current.getCancelAtPeriodEnd())) {
            return current;
        }

        if (current.getProviderSubscriptionId() != null
                && !current.getProviderSubscriptionId().isBlank()
                && isStripe(current.getProvider())) {
            applyStripeCancelAtPeriodEnd(current);
        }

        current.setCancelAtPeriodEnd(true);
        current.setCanceledAt(OffsetDateTime.now());
        subscriptionRepository.save(current);

        if (store.getActiveSubscription() == null || !store.getActiveSubscription().getId().equals(current.getId())) {
            store.setActiveSubscription(current);
        }
        store.setSubscriptionStatus(current.getStatus() != null ? current.getStatus() : store.getSubscriptionStatus());
        storeRepository.save(store);

        log.info("Subscription marked cancel-at-period-end: store={} sub={}", storeId, current.getId());
        return current;
    }

    private SubscriptionPlan resolvePlanOrDefault(String tierCode) {
        if (tierCode != null && !tierCode.isBlank()) {
            Optional<SubscriptionPlan> p = planRepository
                    .findByPlanCode(tierCode.trim().toUpperCase());
            if (p.isPresent()) return p.get();
        }
        return planRepository
                .findByPlanCode("PRO")
                .or(() -> planRepository.findByPlanCode("PLUS"))
                .orElse(null);
    }

    private static boolean isStripe(String provider) {
        return provider != null && PaymentProviderFactory.PROVIDER_STRIPE.equalsIgnoreCase(provider);
    }

    private void applyStripeCancelAtPeriodEnd(Subscription subscription) {
        String key = stripeConfig.getStripeApiKey();
        if (key == null || key.isBlank()) {
            log.warn("Stripe secret key missing, cannot mark provider subscription cancel_at_period_end: sub={}", subscription.getId());
            return;
        }
        String prev = Stripe.apiKey;
        try {
            Stripe.apiKey = key;
            SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(true)
                    .build();
            com.stripe.model.Subscription resource =
                    com.stripe.model.Subscription.retrieve(subscription.getProviderSubscriptionId());
            resource.update(params);
            log.info("Stripe subscription cancel_at_period_end=true: providerSub={}", subscription.getProviderSubscriptionId());
        } catch (StripeException ex) {
            log.error("Failed to mark Stripe subscription cancel_at_period_end: sub={} providerSub={} error={}",
                    subscription.getId(), subscription.getProviderSubscriptionId(), ex.getMessage(), ex);
            throw new IllegalStateException(
                    "Could not cancel subscription with Stripe. Please try again or contact support.", ex);
        } finally {
            Stripe.apiKey = prev;
        }
    }

    public Map<String, Object> getPublicConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brand", Map.of(
                "display_name", brandProperties.getDisplayName()
        ));
        body.put("public_key", stripeConfig.getStripePublicKey());
        body.put("platform_fee_percent", stripeConfig.getPlatformFeePercent());
        body.put("default_provider", PaymentProviderFactory.PROVIDER_STRIPE);
        List<Map<String, Object>> plans = listPlans().stream().map(this::toPlanSummary).toList();
        body.put("plans", plans);
        return body;
    }

    private Map<String, Object> toPlanSummary(SubscriptionPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId() == null ? null : p.getId().toString());
        m.put("plan_code", p.getPlanCode());
        m.put("display_name", p.getDisplayName());
        m.put("description", p.getDescription());
        m.put("price_cents", p.getPriceCents());
        m.put("currency", p.getCurrency());
        m.put("interval_unit", p.getIntervalUnit().name().toLowerCase());
        m.put("interval_count", p.getIntervalCount());
        m.put("trial_days", p.getTrialDays());
        m.put("stripe_price_id", p.getStripePriceId());
        m.put("max_stores", p.getMaxStores());
        m.put("max_runners", p.getMaxRunners());
        m.put("max_products", p.getMaxProducts());
        return m;
    }

    private Store storeExists(UUID storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId.toString()));
    }
}
