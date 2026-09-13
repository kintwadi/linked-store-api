package com.vicinity24.core.linkedstore.api.dto;

import com.vicinity24.core.linkedstore.api.entity.Store;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreAdminResponse {

    private UUID id;
    private String businessName;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String stripeConnectId;
    private Boolean onboarded;
    private String logoUrl;
    private String heroImageUrl;
    private String subscriptionStatus;
    private UUID activeSubscriptionId;
    private OffsetDateTime createdAt;
    private Integer usersCount;
    private Integer transactionCount;

    public static StoreAdminResponse from(Store store, int usersCount, int transactionCount) {
        boolean onboarded = store.getStripeConnectId() != null
                && !store.getStripeConnectId().isBlank()
                && !store.getStripeConnectId().startsWith("acct_connected_new_");
        return StoreAdminResponse.builder()
                .id(store.getId())
                .businessName(store.getBusinessName())
                .latitude(store.getLatitude())
                .longitude(store.getLongitude())
                .stripeConnectId(store.getStripeConnectId())
                .onboarded(onboarded)
                .logoUrl(store.getLogoUrl())
                .heroImageUrl(store.getHeroImageUrl())
                .subscriptionStatus(store.getSubscriptionStatus() != null ? store.getSubscriptionStatus().name() : null)
                .activeSubscriptionId(store.getActiveSubscription() != null ? store.getActiveSubscription().getId() : null)
                .createdAt(store.getCreatedAt())
                .usersCount(usersCount)
                .transactionCount(transactionCount)
                .build();
    }
}
