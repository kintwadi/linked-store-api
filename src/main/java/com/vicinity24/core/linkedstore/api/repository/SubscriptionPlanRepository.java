package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    List<SubscriptionPlan> findByIsActiveTrueOrderByPriceCentsAsc();

    Optional<SubscriptionPlan> findByPlanCode(String planCode);

    Optional<SubscriptionPlan> findByStripePriceId(String stripePriceId);

    @Query("""
            SELECT p FROM SubscriptionPlan p
            WHERE (p.planCode = 'PLUS' OR p.planCode = 'PRO') AND p.isActive = true
            ORDER BY p.priceCents ASC
            """)
    List<SubscriptionPlan> findCorePublicPlans();
}
