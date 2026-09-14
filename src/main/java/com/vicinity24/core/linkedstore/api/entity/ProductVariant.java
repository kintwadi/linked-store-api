package com.vicinity24.core.linkedstore.api.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product_variants",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_variants_store_sku", columnNames = {"store_id", "sku"}))
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", insertable = false, updatable = false)
    private Store store;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "wholesale_price_cents", nullable = false)
    private Integer wholesalePriceCents;

    @Column(name = "retail_price_cents", nullable = false)
    private Integer retailPriceCents;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Type(JsonType.class)
    @Column(name = "variant_attributes", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> variantAttributes = new HashMap<>();

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private VariantStatus status = VariantStatus.ACTIVE;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Type(JsonType.class)
    @Column(name = "gallery_image_urls", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> galleryImageUrls = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (status == null) {
            status = VariantStatus.ACTIVE;
        }
        if (stockQuantity == null) {
            stockQuantity = 0;
        }
        if (variantAttributes == null) {
            variantAttributes = new HashMap<>();
        }
        if (version == null) {
            version = 0;
        }
        if (galleryImageUrls == null) {
            galleryImageUrls = new ArrayList<>();
        }
    }
}
