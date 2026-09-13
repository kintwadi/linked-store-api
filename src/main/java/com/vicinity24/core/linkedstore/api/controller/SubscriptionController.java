package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.SubscriptionPurchaseRequest;
import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.payment.PaymentResponse;
import com.vicinity24.core.linkedstore.api.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getPublicConfig() {
        return ResponseEntity.ok(subscriptionService.getPublicConfig());
    }

    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> listPlans() {
        List<SubscriptionPlan> plans = subscriptionService.listPlans();
        List<Map<String, Object>> body = plans.stream().map(this::toPublicPlan).toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<Map<String, Object>>> listSubscriptions(@PathVariable UUID storeId) {
        List<Subscription> list = subscriptionService.listStoreSubscriptions(storeId);
        return ResponseEntity.ok(list.stream().map(this::toPublicSubscription).toList());
    }

    @GetMapping("/store/{storeId}/current")
    public ResponseEntity<Map<String, Object>> getCurrent(@PathVariable UUID storeId) {
        return subscriptionService.getCurrentStoreSubscription(storeId)
                .<ResponseEntity<Map<String, Object>>>map(s -> ResponseEntity.ok(toPublicSubscription(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/store/{storeId}/purchase")
    public ResponseEntity<Map<String, Object>> purchase(
            @PathVariable UUID storeId,
            @RequestBody SubscriptionPurchaseRequest request) {
        PaymentResponse payment = subscriptionService.purchaseSubscription(
                storeId,
                request.getPlanCode(),
                request.getProvider(),
                request.getPaymentMethodId(),
                request.getCustomerEmail(),
                request.getIdempotencyKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("store_id", storeId.toString());
        body.put("plan_code", request.getPlanCode());
        body.put("provider", payment.getProvider());
        body.put("payment_intent_id", payment.getPaymentIntentId());
        body.put("client_secret", payment.getClientSecret());
        body.put("amount_cents", payment.getAmountCents());
        body.put("currency", payment.getCurrency());
        body.put("status", payment.getStatus());
        body.put("paid_at", payment.getCreatedAt() == null ? null : payment.getCreatedAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/store/{storeId}/trial/{planCode}")
    public ResponseEntity<Map<String, Object>> startFreeTrial(
            @PathVariable UUID storeId,
            @PathVariable String planCode) {
        Subscription sub = subscriptionService.activateFreeTrial(storeId, planCode);
        return ResponseEntity.status(HttpStatus.CREATED).body(toPublicSubscription(sub));
    }

    @PostMapping("/store/{storeId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID storeId) {
        Subscription sub = subscriptionService.cancelAtPeriodEnd(storeId);
        return ResponseEntity.ok(toPublicSubscription(sub));
    }

    private Map<String, Object> toPublicPlan(SubscriptionPlan p) {
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
        return m;
    }

    private Map<String, Object> toPublicSubscription(Subscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId().toString());
        m.put("store_id", s.getStoreId().toString());
        m.put("plan_code", s.getPlan() == null ? null : s.getPlan().getPlanCode());
        m.put("status", s.getStatus().name());
        m.put("provider", s.getProvider());
        m.put("provider_subscription_id", s.getProviderSubscriptionId());
        m.put("current_period_start", s.getCurrentPeriodStart() == null ? null : s.getCurrentPeriodStart().toString());
        m.put("current_period_end", s.getCurrentPeriodEnd() == null ? null : s.getCurrentPeriodEnd().toString());
        m.put("trial_start", s.getTrialStart() == null ? null : s.getTrialStart().toString());
        m.put("trial_end", s.getTrialEnd() == null ? null : s.getTrialEnd().toString());
        m.put("cancel_at_period_end", s.getCancelAtPeriodEnd());
        m.put("canceled_at", s.getCanceledAt() == null ? null : s.getCanceledAt().toString());
        m.put("ended_at", s.getEndedAt() == null ? null : s.getEndedAt().toString());
        return m;
    }
}
