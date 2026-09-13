package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.Product;
import com.vicinity24.core.linkedstore.api.entity.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findAllByStatus(ProductStatus status);

    @Query(value = """
        SELECT * FROM products
        WHERE status = :status
          AND attributes @> CAST(:attributesJsonb AS jsonb)
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<Product> findByAttributesContaining(
            @Param("attributesJsonb") String attributesJsonb,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Query(value = """
        SELECT COUNT(*) FROM products
        WHERE status = :status
          AND attributes @> CAST(:attributesJsonb AS jsonb)
        """, nativeQuery = true)
    long countByAttributesContaining(
            @Param("attributesJsonb") String attributesJsonb,
            @Param("status") String status
    );

    @Query(value = """
        SELECT * FROM products
        WHERE status = :status
          AND (
              attributes @> CAST(:attributesJsonb AS jsonb)
              OR title ILIKE CONCAT('%', :searchTerm, '%')
              OR description ILIKE CONCAT('%', :searchTerm, '%')
          )
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<Product> searchProducts(
            @Param("attributesJsonb") String attributesJsonb,
            @Param("searchTerm") String searchTerm,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
