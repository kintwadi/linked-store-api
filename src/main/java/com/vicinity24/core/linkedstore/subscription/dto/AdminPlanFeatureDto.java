package com.vicinity24.core.linkedstore.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdminPlanFeatureDto {
    private UUID id;
    private String label;
    private Boolean included;
    private Boolean highlight;
    private Integer displayOrder;
}
