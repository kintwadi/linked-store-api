package com.vicinity24.core.linkedstore.api.refund.dto;

import com.vicinity24.core.linkedstore.api.refund.entity.Refund;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID transactionId,
        String status,
        Integer totalRefundedCents,
        Integer reversedFulfillerCents,
        Integer reversedOriginatorCents,
        String currency,
        String reason,
        String stripeRefundId,
        String stripeError,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        String perspective,
        Integer perspectiveReversedCents
) {
    public static RefundResponse from(Refund r, String perspective) {
        int prc;
        switch (perspective) {
            case "ORIGINATOR_HOST":
                prc = r.getReversedOriginatorCents() == null ? 0 : r.getReversedOriginatorCents();
                break;
            case "FULFILLER_SELLER":
                prc = r.getReversedFulfillerCents() == null ? 0 : r.getReversedFulfillerCents();
                break;
            default:
                prc = r.getTotalRefundedCents() == null ? 0 : r.getTotalRefundedCents();
                break;
        }
        return new RefundResponse(
                r.getId(),
                r.getTransactionId(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getTotalRefundedCents(),
                r.getReversedFulfillerCents(),
                r.getReversedOriginatorCents(),
                r.getCurrency(),
                r.getReason(),
                r.getStripeRefundId(),
                r.getStripeError(),
                r.getCreatedAt(),
                r.getCompletedAt(),
                perspective,
                prc
        );
    }
}
