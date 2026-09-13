package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.CreateStoreRequest;
import com.vicinity24.core.linkedstore.api.dto.StoreAdminResponse;
import com.vicinity24.core.linkedstore.api.dto.UpdateStoreRequest;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
public class AdminStoreController {

    private final StoreRepository storeRepository;
    private final UserAccountRepository userAccountRepository;
    private final TransactionRepository transactionRepository;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping("")
    public ResponseEntity<?> listStores() {
        authenticationFacade.requireGlobalAdmin();
        List<Store> stores = storeRepository.findAll();
        List<StoreAdminResponse> result = new ArrayList<>();
        for (Store store : stores) {
            result.add(buildStoreAdminResponse(store));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getStore(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @PostMapping("")
    @Transactional
    public ResponseEntity<?> createStore(@Valid @RequestBody CreateStoreRequest request) {
        authenticationFacade.requireGlobalAdmin();
        SubscriptionStatus subStatus = SubscriptionStatus.ACTIVE;
        if (request.getSubscriptionStatus() != null && !request.getSubscriptionStatus().isBlank()) {
            subStatus = SubscriptionStatus.valueOf(request.getSubscriptionStatus());
        }
        Store store = Store.builder()
                .businessName(request.getBusinessName())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .stripeConnectId(request.getStripeConnectId())
                .subscriptionStatus(subStatus)
                .logoUrl(request.getLogoUrl())
                .heroImageUrl(request.getHeroImageUrl())
                .build();
        store = storeRepository.save(store);
        log.info("Global admin created store id={} businessName={}",
                store.getId(), store.getBusinessName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(buildStoreAdminResponse(store));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateStore(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateStoreRequest request) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));

        if (request.getBusinessName() != null) {
            store.setBusinessName(request.getBusinessName());
        }
        if (request.getLatitude() != null) {
            store.setLatitude(request.getLatitude());
        }
        if (request.getLongitude() != null) {
            store.setLongitude(request.getLongitude());
        }
        if (request.getStripeConnectId() != null) {
            store.setStripeConnectId(request.getStripeConnectId());
        }
        if (request.getSubscriptionStatus() != null) {
            try {
                store.setSubscriptionStatus(SubscriptionStatus.valueOf(request.getSubscriptionStatus()));
            } catch (IllegalArgumentException ignored) {
                // keep existing subscription status
            }
        }
        if (request.getLogoUrl() != null) {
            store.setLogoUrl(request.getLogoUrl());
        }
        if (request.getHeroImageUrl() != null) {
            store.setHeroImageUrl(request.getHeroImageUrl());
        }

        store = storeRepository.save(store);
        log.info("Global admin updated store id={}", id);
        return ResponseEntity.ok(buildStoreAdminResponse(store));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteStore(@PathVariable("id") UUID id) {
        authenticationFacade.requireGlobalAdmin();
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Store", id.toString()));
        store.setSubscriptionStatus(SubscriptionStatus.CANCELED);
        store.setActiveSubscription(null);
        storeRepository.save(store);
        log.info("Global admin soft-deleted store id={} (subscriptionStatus=CANCELED)", id);
        return ResponseEntity.noContent().build();
    }

    private StoreAdminResponse buildStoreAdminResponse(Store store) {
        UUID storeId = store.getId();
        long userCount = userAccountRepository.findByStoreId(storeId).size();
        long txOriginating = transactionRepository.findByOriginatingStoreId(storeId).size();
        long txFulfilling = transactionRepository.findByFulfillingStoreId(storeId).size();
        long transactionCount = txOriginating + txFulfilling;

        return StoreAdminResponse.builder()
                .id(store.getId())
                .businessName(store.getBusinessName())
                .latitude(store.getLatitude())
                .longitude(store.getLongitude())
                .stripeConnectId(store.getStripeConnectId())
                .subscriptionStatus(store.getSubscriptionStatus() != null
                        ? store.getSubscriptionStatus().name()
                        : null)
                .logoUrl(store.getLogoUrl())
                .heroImageUrl(store.getHeroImageUrl())
                .activeSubscriptionId(store.getActiveSubscription() != null
                        ? store.getActiveSubscription().getId()
                        : null)
                .usersCount((int) userCount)
                .transactionCount((int) transactionCount)
                .createdAt(store.getCreatedAt())
                .build();
    }
}
