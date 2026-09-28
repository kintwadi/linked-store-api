package com.vicinity24.core.linkedstore.api.returns.dto;

public record ErrorResponse(
        String error,
        Integer httpStatus,
        String detail
) {}
