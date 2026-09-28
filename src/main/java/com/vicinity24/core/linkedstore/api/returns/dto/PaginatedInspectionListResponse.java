package com.vicinity24.core.linkedstore.api.returns.dto;

import java.util.List;

public record PaginatedInspectionListResponse(
        List<InspectionSummaryResponse> content,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages,
        InspectionCountsResponse counts
) {}

