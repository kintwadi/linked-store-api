package com.vicinity24.core.linkedstore.api.refund.dto;

import java.util.Set;

public record RefundedTxIdSetResponse(
        Set<String> transactionIds
) {}
