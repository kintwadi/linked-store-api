package com.vicinity24.core.linkedstore.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UpsertPlanFeatureRequest {
    private String label;
    private Boolean included;
    private Boolean highlight;
    private Integer displayOrder;
}
