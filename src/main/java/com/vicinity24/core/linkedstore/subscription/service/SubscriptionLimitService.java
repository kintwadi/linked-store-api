package com.vicinity24.core.linkedstore.subscription.service;

import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionPlanRepository;
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
    private final SubscriptionPlanRepository planRepository;

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
        SubscriptionPlan plan = resolveEffectivePlan(storeId);
        PlanTier tier = planToTier(plan);
        Integer limit = resolveMonthlyOrderLimit(plan, tier);

        if (limit == null || limit < 0) {
            log.info("{} tier: no monthly order cap for store {}", tier, storeId);
            return;
        }

        long currentLong = countCurrentMonthOrders(storeId);
        int current = (int) Math.min(currentLong, Integer.MAX_VALUE);

        if (Long.compare(currentLong, (long) limit) >= 0) {
            log.warn("order limit exceeded storeId={} currentCount={} limit={} tier={}",
                    storeId, current, limit, tier);
            throw new OrderLimitExceededException(storeId, current, limit);
        }

        log.debug("order quota check passed storeId={} currentCount={} limit={} remaining={}",
                storeId, current, limit, Math.max(0, limit - current));
    }

    public Map<String, Object> getPlanInfo(UUID storeId) {
        SubscriptionPlan plan = resolveEffectivePlan(storeId);
        PlanTier tier = planToTier(plan);
        long currentCount = countCurrentMonthOrders(storeId);
        Integer limit = normalizeUnlimited(resolveMonthlyOrderLimit(plan, tier));

        Map<String, Object> info = new HashMap<>();
        info.put("tier", tier.name());
        info.put("planCode", tier.getPlanCode());
        info.put("displayName", plan != null && plan.getDisplayName() != null ? plan.getDisplayName() : tier.getDisplayName());
        info.put("isEnterprise", tier.isEnterprise());
        info.put("monthlyOrderLimit", limit);
        info.put("currentMonthOrders", currentCount);
        info.put("remainingOrders", limit != null ? Math.max(0, limit - (int) currentCount) : null);
        return info;
    }

    private static Integer normalizeUnlimited(Integer v) {
        if (v == null) return null;
        if (v < 0 || v.equals(Integer.MAX_VALUE)) return null;
        return v;
    }

    private SubscriptionPlan resolveEffectivePlan(UUID storeId) {
        List<Subscription> activeSubs = subscriptionRepository.findActiveOrGraceByStoreId(storeId);
        for (Subscription sub : activeSubs) {
            SubscriptionPlan plan = sub.getPlan();
            if (plan != null && plan.getPlanCode() != null) {
                return plan;
            }
        }
        try {
            return planRepository.findByPlanCode("PRO").orElse(null);
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    private PlanTier planToTier(SubscriptionPlan plan) {
        if (plan == null || plan.getPlanCode() == null) return PlanTier.PRO;
        String code = plan.getPlanCode().toUpperCase();
        if (code.contains("CUSTOM")) return PlanTier.CUSTOM;
        return PlanTier.PRO;
    }

    private Integer resolveMonthlyOrderLimit(SubscriptionPlan plan, PlanTier tier) {
        if (tier == PlanTier.CUSTOM) {
            if (plan != null && plan.getMonthlyOrderLimit() != null) {
                return plan.getMonthlyOrderLimit();
            }
            return null;
        }
        if (plan != null && plan.getMonthlyOrderLimit() != null) {
            return plan.getMonthlyOrderLimit();
        }
        return tierSettings.getPro().getMonthlyOrderLimit();
    }
}
