package com.vicinity24.core.linkedstore.api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stores", indexes = {
        @Index(name = "idx_stores_gateway_code", columnList = "gateway_code", unique = true)
})
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_name", nullable = false, length = 255)
    private String businessName;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "stripe_connect_id", nullable = false, length = 255)
    private String stripeConnectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 50)
    @Builder.Default
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.ACTIVE;

    @Column(name = "logo_url", length = 1024)
    private String logoUrl;

    @Column(name = "hero_image_url", length = 1024)
    private String heroImageUrl;

    @Column(name = "address")
    private String address;

    @Column(name = "postal_code", length = 32)
    private String postalCode;

    @Column(name = "gateway_code", unique = true, nullable = false, length = 16)
    private String gatewayCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_subscription_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "fk_stores_active_subscription"))
    private Subscription activeSubscription;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (subscriptionStatus == null) {
            subscriptionStatus = SubscriptionStatus.ACTIVE;
        }
        if (countryCode == null) {
            countryCode = "US";
        }
        if (currencyCode == null) {
            currencyCode = "USD";
        }
        if (address == null) {
            address = "";
        }
        if (postalCode == null) {
            postalCode = "";
        }
        if (gatewayCode == null || gatewayCode.isBlank()) {
            gatewayCode = generateGatewayCode();
        }
    }

    public static String generateGatewayCode() {
        SecureRandom sr = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append((char) ('0' + sr.nextInt(10)));
        }
        return sb.toString();
    }
}
