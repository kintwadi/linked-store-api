package com.vicinity24.core.linkedstore.api.service;

import com.vicinity24.core.linkedstore.api.config.PlatformContractConfig;
import com.vicinity24.core.linkedstore.api.entity.PlatformContract;
import com.vicinity24.core.linkedstore.api.entity.PlatformContractMode;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import com.vicinity24.core.linkedstore.api.repository.PlatformContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformContractService {

    private static final Long SINGLETON_ID = 1L;

    private final PlatformContractRepository repository;
    private final PlatformContractConfig config;

    @Transactional(readOnly = true)
    public PlatformContract getEffectiveContract() {
        return repository.findById(SINGLETON_ID).orElseGet(this::seedFromEnv);
    }

    @Transactional
    public PlatformContract seedFromEnv() {
        return repository.findById(SINGLETON_ID).orElseGet(() -> {
            PlatformContract seeded = PlatformContract.builder()
                    .id(SINGLETON_ID)
                    .mode(config.getResolvedMode())
                    .feePercentage(config.getResolvedFeePercentage())
                    .platformPayoutConnectId(null)
                    .updatedAt(OffsetDateTime.now())
                    .build();
            return repository.save(seeded);
        });
    }

    @Transactional
    public PlatformContract updateContract(PlatformContractMode mode, Double feePercentage,
                                           String platformPayoutConnectId) {
        if (mode == null) {
            throw new IllegalArgumentException("Contract mode must not be null.");
        }
        if (mode == PlatformContractMode.FEE_BASED) {
            if (feePercentage == null || feePercentage < 0 || feePercentage > 100) {
                throw new IllegalArgumentException("Fee percentage must be between 0 and 100.");
            }
        }
        PlatformContract existing = repository.findById(SINGLETON_ID)
                .orElseGet(() -> PlatformContract.builder().id(SINGLETON_ID).build());
        existing.setMode(mode);
        existing.setFeePercentage(feePercentage != null ? feePercentage : 0.0);
        existing.setPlatformPayoutConnectId(platformPayoutConnectId);
        existing.setUpdatedAt(OffsetDateTime.now());
        PlatformContract saved = repository.save(existing);
        log.info("Platform contract updated: mode={}, feePercentage={}%, payoutConnectId={}",
                saved.getMode(), saved.getFeePercentage(), saved.getPlatformPayoutConnectId());
        return saved;
    }

    public boolean isFeeBased() {
        return getEffectiveContract().getMode() == PlatformContractMode.FEE_BASED;
    }

    public boolean isSubscriptionBased() {
        return getEffectiveContract().getMode() == PlatformContractMode.SUBSCRIPTION;
    }

    public boolean isConfigured() {
        PlatformContractMode mode = getEffectiveContract().getMode();
        return mode == PlatformContractMode.FEE_BASED || mode == PlatformContractMode.SUBSCRIPTION;
    }

    public int computePlatformFeeCents(int arbitrageMarginCents) {
        if (!isFeeBased() || arbitrageMarginCents <= 0) {
            return 0;
        }
        double pct = getEffectiveContract().getFeePercentage();
        return (int) Math.round(arbitrageMarginCents * (pct / 100.0));
    }

    public boolean canStoreTransact(Store store) {
        if (store == null) return false;
        PlatformContractMode mode = getEffectiveContract().getMode();
        if (mode == PlatformContractMode.FEE_BASED) {
            return true;
        }
        if (mode == PlatformContractMode.SUBSCRIPTION) {
            return hasActiveSubscription(store);
        }
        return false;
    }

    private boolean hasActiveSubscription(Store store) {
        Subscription active = store.getActiveSubscription();
        if (active != null) {
            SubscriptionStatus status = active.getStatus();
            return status == SubscriptionStatus.ACTIVE
                    || status == SubscriptionStatus.TRIALING
                    || status == SubscriptionStatus.PAST_DUE;
        }
        SubscriptionStatus storeStatus = store.getSubscriptionStatus();
        return storeStatus == SubscriptionStatus.ACTIVE
                || storeStatus == SubscriptionStatus.TRIALING
                || storeStatus == SubscriptionStatus.PAST_DUE;
    }
}
