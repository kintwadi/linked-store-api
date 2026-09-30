package com.vicinity24.core.linkedstore.api.returns.dto;

public record InspectionCountsResponse(
        Integer underInspectionCount,
        Integer passedCount,
        Integer rejectedCount,
        Integer restockedCount,
        Integer totalCount
) {}

