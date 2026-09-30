package com.vicinity24.core.linkedstore.api.refund.dto;

import jakarta.validation.constraints.Max;

public record InitiateRefundRequest(
        @Max(10000000)
        Integer amountCents,
        String reason
) {}
