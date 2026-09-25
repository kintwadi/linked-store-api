package com.vicinity24.core.linkedstore.api.dto;

public enum TxEventType {
    REQUESTED,
    FULFILLER_ACCEPTED,
    FULFILLER_REJECTED,
    RESERVED,
    READY,
    UNAVAILABLE,
    PICKED_UP,
    PAID,
    CANCELLED,
    EXPIRED
}
