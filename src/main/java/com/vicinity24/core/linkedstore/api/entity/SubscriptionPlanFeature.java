package com.vicinity24.core.linkedstore.api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "subscription_plan_features", uniqueConstraints = {
        @UniqueConstraint(name = "uk_plan_features_order", columnNames = {"plan_id", "display_order"})
})
public class SubscriptionPlanFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false, foreignKey = @ForeignKey(name = "fk_plan_features_plan_id", value = ConstraintMode.CONSTRAINT))
    private SubscriptionPlan plan;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "included", nullable = false)
    @Builder.Default
    private Boolean included = true;

    @Column(name = "highlight", nullable = false)
    @Builder.Default
    private Boolean highlight = false;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;
}
