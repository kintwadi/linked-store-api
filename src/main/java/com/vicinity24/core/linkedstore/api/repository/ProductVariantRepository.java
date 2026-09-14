package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.VariantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByProductId(UUID productId);

    List<ProductVariant> findByStoreIdAndStatus(UUID storeId, VariantStatus status);

    List<ProductVariant> findByProductIdAndStatusOrderByRetailPriceCentsAsc(UUID productId, VariantStatus status);

    @Query(value = """
        SELECT pv.* FROM product_variants pv
        WHERE pv.product_id = :productId
          AND pv.status = :status
          AND pv.store_id != :excludeStoreId
          AND pv.stock_quantity >= :minQuantity
          AND pv.variant_attributes @> CAST(:variantAttributesJsonb AS jsonb)
        ORDER BY pv.retail_price_cents ASC
        """, nativeQuery = true)
    List<ProductVariant> findAvailableVariantsWithMatchingAttributes(
            @Param("productId") UUID productId,
            @Param("excludeStoreId") UUID excludeStoreId,
            @Param("variantAttributesJsonb") String variantAttributesJsonb,
            @Param("status") String status,
            @Param("minQuantity") int minQuantity
    );

    @Query(value = """
        SELECT pv.* FROM product_variants pv
        WHERE pv.id = :variantId
          AND pv.stock_quantity >= :quantity
        """, nativeQuery = true)
    Optional<ProductVariant> findByIdWithSufficientStock(
            @Param("variantId") UUID variantId,
            @Param("quantity") int quantity
    );

    @Modifying
    @Query("""
        UPDATE ProductVariant pv
        SET pv.stockQuantity = pv.stockQuantity - :quantity,
            pv.version = pv.version + 1
        WHERE pv.id = :variantId
          AND pv.version = :expectedVersion
          AND pv.stockQuantity >= :quantity
        """)
    int decrementStockWithOptimisticLock(
            @Param("variantId") UUID variantId,
            @Param("quantity") int quantity,
            @Param("expectedVersion") int expectedVersion
    );

    @Modifying
    @Query("""
        UPDATE ProductVariant pv
        SET pv.stockQuantity = pv.stockQuantity + :quantity
        WHERE pv.id = :variantId
          AND pv.stockQuantity >= 0
        """)
    int restoreStock(
            @Param("variantId") UUID variantId,
            @Param("quantity") int quantity
    );

    @Query("""
        SELECT pv FROM ProductVariant pv
        JOIN FETCH pv.product
        WHERE pv.id = :variantId
    """)
    Optional<ProductVariant> findByIdWithProduct(@Param("variantId") UUID variantId);

    List<ProductVariant> findByStoreId(UUID storeId);

    Optional<ProductVariant> findByIdAndStoreId(UUID id, UUID storeId);
}
