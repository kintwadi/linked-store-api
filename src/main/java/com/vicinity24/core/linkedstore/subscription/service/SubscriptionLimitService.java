package com.vicinity24.core.linkedstore.subscription.service;

import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionRepository;
import com.vicinity24.core.linkedstore.subscription.PlanTier;
import com.vicinity24.core.linkedstore.subscription.SubscriptionTierSettings;
import com.vicinity24.core.linkedstore.subscription.exception.OrderLimitExceededException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLimitService {

    private final SubscriptionTierSettings tierSettings;
    private final EntityManager entityManager;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Counts processed orders for a store in the current calendar month.
     *
     * We include BOTH sides of a transaction — originatingStoreId (the broker store
     * that listed/resold the product) AND fulfillingStoreId (the store that actually
     * owns and fulfills the inventory) — because each involvement represents a
     * processed order that consumes quota: a broker pays for the right to resell into
     * the network, and a fulfiller pays for the right to receive demand from it.
     */
    public long countCurrentMonthOrders(UUID storeId) {
        OffsetDateTime startOfMonth = OffsetDateTime.now()
                .withDayOfMonth(1)
                .with(LocalTime.MIN);
        OffsetDateTime endOfMonth = startOfMonth
                .plusMonths(1)
                .minusNanos(1);

        String jpql = """
                SELECT COUNT(t) FROM Transaction t
                WHERE (t.originatingStoreId = :sid OR t.fulfillingStoreId = :sid)
                  AND t.status IN (:paid, :pickedUp)
                  AND t.createdAt BETWEEN :startOfMonth AND :endOfMonth
                """;

        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        query.setParameter("sid", storeId);
        query.setParameter("paid", TransactionStatus.PAID);
        query.setParameter("pickedUp", TransactionStatus.PICKED_UP);
        query.setParameter("startOfMonth", startOfMonth);
        query.setParameter("endOfMonth", endOfMonth);

        Long result = query.getSingleResult();
        return result != null ? result : 0L;
    }

    public void checkOrderLimit(UUID storeId) {
        PlanTier tier = determineEffectiveTier(storeId);

        if (tier == PlanTier.CUSTOM) {
            log.info("enterprise tier: no order cap for store {}", storeId);
            return;
        }

        Integer limitSetting = tierSettings.getPro().getMonthlyOrderLimit();
        if (limitSetting == null || limitSetting < 0) {
            log.info("PRO tier: no monthly order cap configured for store {}", storeId);
            return;
        }
        int limit = limitSetting;

        long currentLong = countCurrentMonthOrders(storeId);
        int current = (int) Math.min(currentLong, Integer.MAX_VALUE);

        if (Long.compare(currentLong, (long) limit) >= 0) {
            log.warn("order limit exceeded storeId={} currentCount={} limit={} tier=PRO",
                    storeId, current, limit);
            throw new OrderLimitExceededException(storeId, current, limit);
        }

        log.debug("order quota check passed storeId={} currentCount={} limit={} remaining={}",
                storeId, current, limit, Math.max(0, limit - current));
    }

    public Map<String, Object> getPlanInfo(UUID storeId) {
        PlanTier tier = determineEffectiveTier(storeId);
        long currentCount = countCurrentMonthOrders(storeId);
        Integer limit = tier == PlanTier.PRO ? tierSettings.getPro().getMonthlyOrderLimit() : null;

        Map<String, Object> info = new HashMap<>();
        info.put("tier", tier.name());
        info.put("planCode", tier.getPlanCode());
        info.put("displayName", tier.getDisplayName());
        info.put("isEnterprise", tier.isEnterprise());
        info.put("monthlyOrderLimit", limit);
        info.put("currentMonthOrders", currentCount);
        info.put("remainingOrders", limit != null ? Math.max(0, limit - (int) currentCount) : null);
        return info;
    }

    private PlanTier determineEffectiveTier(UUID storeId) {
        List<Subscription> activeSubs = subscriptionRepository.findActiveOrGraceByStoreId(storeId);
        for (Subscription sub : activeSubs) {
            SubscriptionPlan plan = sub.getPlan();
            if (plan != null && plan.getPlanCode() != null) {
                String code = plan.getPlanCode().toUpperCase();
                if (code.contains("CUSTOM")) {
                    return PlanTier.CUSTOM;
                }
                if (code.contains("PRO")) {
                    return PlanTier.PRO;
                }
            }
        }
        return PlanTier.PRO;
    }
}
