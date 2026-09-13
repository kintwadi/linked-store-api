package com.vicinity24.core.linkedstore.api.controller;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.dto.StoreSummaryResponse;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreRepository storeRepository;
    private final StripeConfig stripeConfig;

    @GetMapping("")
    public ResponseEntity<List<StoreSummaryResponse>> listStores() {

        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }

        List<Store> stores = storeRepository.findAll();
        List<StoreSummaryResponse> result = new ArrayList<>();

        for (Store store : stores) {
            boolean onboarded = store.getStripeConnectId() != null
                    && !store.getStripeConnectId().isBlank()
                    && !store.getStripeConnectId().startsWith("acct_connected_");

            boolean chargesEnabled = false;
            boolean payoutsEnabled = false;

            if (onboarded) {
                try {
                    Account account = Account.retrieve(store.getStripeConnectId());
                    if (account.getChargesEnabled() != null) {
                        chargesEnabled = account.getChargesEnabled();
                    }
                    if (account.getPayoutsEnabled() != null) {
                        payoutsEnabled = account.getPayoutsEnabled();
                    }
                } catch (StripeException e) {
                    log.warn("Failed to retrieve Stripe account status for storeId={}, connectId={}",
                            store.getId(), store.getStripeConnectId(), e);
                }
            }

            result.add(StoreSummaryResponse.builder()
                    .id(store.getId())
                    .businessName(store.getBusinessName())
                    .latitude(store.getLatitude())
                    .longitude(store.getLongitude())
                    .stripeConnectId(store.getStripeConnectId())
                    .onboarded(onboarded)
                    .chargesEnabled(chargesEnabled)
                    .payoutsEnabled(payoutsEnabled)
                    .subscriptionStatus(store.getSubscriptionStatus().name())
                    .logoUrl(store.getLogoUrl())
                    .build());
        }

        return ResponseEntity.ok(result);
    }
}
