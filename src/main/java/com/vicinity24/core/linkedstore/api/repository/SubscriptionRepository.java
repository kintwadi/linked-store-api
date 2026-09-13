package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    @Query("""
            SELECT s FROM Subscription s
            WHERE s.storeId = :storeId
              AND s.status IN ('ACTIVE','TRIALING','PAST_DUE')
            ORDER BY s.createdAt DESC
            """)
    List<Subscription> findActiveOrGraceByStoreId(UUID storeId);

    Optional<Subscription> findFirstByStoreIdOrderByCreatedAtDesc(UUID storeId);

    Optional<Subscription> findByProviderSubscriptionId(String providerSubscriptionId);

    List<Subscription> findByStoreId(UUID storeId);

    long countByStoreIdAndStatusIn(UUID storeId, java.util.Collection<SubscriptionStatus> statuses);
}
