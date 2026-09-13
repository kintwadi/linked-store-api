package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoreRepository extends JpaRepository<Store, UUID> {

    Optional<Store> findByStripeConnectId(String stripeConnectId);

    Optional<Store> findByBusinessName(String businessName);

    List<Store> findAllByBusinessName(String businessName);

    List<Store> findAllBySubscriptionStatus(SubscriptionStatus subscriptionStatus);

    void deleteByStripeConnectIdStartingWith(String prefix);

    @Query(value = """
        SELECT * FROM stores
        WHERE subscription_status = :status
          AND earth_distance(
                ll_to_earth(latitude, longitude),
                ll_to_earth(:lat, :lng)
              ) <= :radiusMeters
        ORDER BY earth_distance(
                   ll_to_earth(latitude, longitude),
                   ll_to_earth(:lat, :lng)
                 ) ASC
        """, nativeQuery = true)
    List<Store> findStoresWithinRadius(
            @Param("lat") BigDecimal latitude,
            @Param("lng") BigDecimal longitude,
            @Param("radiusMeters") double radiusMeters,
            @Param("status") String status
    );

    @Query(value = """
        SELECT s.* FROM stores s
        WHERE s.id != :excludeStoreId
          AND s.subscription_status = :status
        ORDER BY (
            POW(111.1 * (CAST(s.latitude AS DOUBLE PRECISION) - CAST(:lat AS DOUBLE PRECISION)), 2) +
            POW(111.1 * (CAST(:lng AS DOUBLE PRECISION) - CAST(s.longitude AS DOUBLE PRECISION)) *
                COS(CAST(s.latitude AS DOUBLE PRECISION) / 57.3), 2)
        ) ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Store> findNearestStoresExcluding(
            @Param("excludeStoreId") UUID excludeStoreId,
            @Param("lat") BigDecimal latitude,
            @Param("lng") BigDecimal longitude,
            @Param("status") String status,
            @Param("limit") int limit
    );
}
