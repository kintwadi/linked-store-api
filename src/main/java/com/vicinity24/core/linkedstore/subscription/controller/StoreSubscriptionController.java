package com.vicinity24.core.linkedstore.subscription.controller;

import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.service.SubscriptionService;
import com.vicinity24.core.linkedstore.subscription.dto.SubscriptionCheckoutRequest;
import com.vicinity24.core.linkedstore.subscription.dto.SubscriptionCheckoutResponse;
import com.vicinity24.core.linkedstore.subscription.service.SubscriptionCheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/subscription/v1")
@RequiredArgsConstructor
@CrossOrigin(value = "*", maxAge = 3600)
public class StoreSubscriptionController {

    private final SubscriptionCheckoutService checkoutService;
    private final SubscriptionService subscriptionService;
    private final StoreRepository storeRepository;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/checkout-session")
    public ResponseEntity<SubscriptionCheckoutResponse> createSubscriptionCheckout(
            @Valid @RequestBody SubscriptionCheckoutRequest request) {
        try {
            SubscriptionCheckoutResponse response = checkoutService.createCheckout(request);
            if ("CONTACT_SALES".equalsIgnoreCase(response.getType())) {
                return ResponseEntity.status(200).body(response);
            }
            return ResponseEntity.status(201).body(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            SubscriptionCheckoutResponse err = SubscriptionCheckoutResponse.builder()
                    .type("ERROR")
                    .message(ex.getMessage())
                    .storeId(request.getStoreId() != null ? request.getStoreId().toString() : null)
                    .planCode(request.getPlanCode())
                    .interval(request.getInterval())
                    .build();
            return ResponseEntity.badRequest().body(err);
        }
    }

    @GetMapping("/store/{storeId}")
    public ResponseEntity<Map<String, Object>> getCurrent(@PathVariable UUID storeId) {
        authenticationFacade.requireCanManageSubscription(storeId);
        Store store = storeRepository.findById(storeId).orElse(null);
        if (store == null) return ResponseEntity.notFound().build();
        Subscription active = store.getActiveSubscription();
        if (active == null) {
            active = subscriptionService.getCurrentStoreSubscription(storeId).orElse(null);
        }
        SubscriptionStatus storeStatus = store.getSubscriptionStatus();
        boolean isSubscribed = active != null
                && active.getCanceledAt() == null
                && active.getEndedAt() == null
                && (active.getStatus() == SubscriptionStatus.ACTIVE
                    || active.getStatus() == SubscriptionStatus.TRIALING
                    || active.getStatus() == SubscriptionStatus.PAST_DUE);
        if (!isSubscribed && storeStatus != null) {
            isSubscribed = storeStatus == SubscriptionStatus.ACTIVE
                    || storeStatus == SubscriptionStatus.TRIALING
                    || storeStatus == SubscriptionStatus.PAST_DUE;
        }
        String fallbackStatus =
                storeStatus != null
                        ? storeStatus.name()
                        : active != null && active.getStatus() != null
                                ? active.getStatus().name()
                                : SubscriptionStatus.FREE.name();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", storeId.toString());
        body.put("isSubscribed", isSubscribed);
        body.put("status", active != null && active.getStatus() != null
                ? active.getStatus().name() : fallbackStatus);
        body.put("cancelAtPeriodEnd", active != null && Boolean.TRUE.equals(active.getCancelAtPeriodEnd()));
        body.put("canceledAt", active != null && active.getCanceledAt() != null
                ? active.getCanceledAt().toString() : null);
        body.put("currentPeriodEnd", active != null && active.getCurrentPeriodEnd() != null
                ? active.getCurrentPeriodEnd().toString() : null);
        body.put("currentPeriodStart", active != null && active.getCurrentPeriodStart() != null
                ? active.getCurrentPeriodStart().toString() : null);
        body.put("trialEnd", active != null && active.getTrialEnd() != null
                ? active.getTrialEnd().toString() : null);
        body.put("provider", active != null ? active.getProvider() : null);
        body.put("providerSubscriptionId", active != null ? active.getProviderSubscriptionId() : null);
        String planCodeResolved = active != null && active.getPlan() != null
                ? active.getPlan().getPlanCode()
                : (isSubscribed ? "PRO" : null);
        body.put("planCode", planCodeResolved);
        body.put("planDisplayName", active != null && active.getPlan() != null
                ? active.getPlan().getDisplayName()
                : (isSubscribed ? "Standard subscription" : null));
        body.put("updatedAt", OffsetDateTime.now().toString());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/store/{storeId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelAtPeriodEnd(@PathVariable UUID storeId) {
        authenticationFacade.requireCanManageSubscription(storeId);
        try {
            Subscription s = subscriptionService.cancelAtPeriodEnd(storeId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("storeId", storeId.toString());
            body.put("canceled", true);
            body.put("cancelAtPeriodEnd", Boolean.TRUE.equals(s.getCancelAtPeriodEnd()));
            body.put("canceledAt", s.getCanceledAt() != null ? s.getCanceledAt().toString() : null);
            body.put("status", s.getStatus() == null ? null : s.getStatus().name());
            body.put("currentPeriodEnd", s.getCurrentPeriodEnd() != null ? s.getCurrentPeriodEnd().toString() : null);
            body.put("message", "Subscription will remain active until the end of the current billing period.");
            return ResponseEntity.ok(body);
        } catch (RuntimeException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("storeId", storeId.toString());
            err.put("canceled", false);
            err.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }
    }
}
