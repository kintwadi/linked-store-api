package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreTransactionResponse {

    private UUID id;

    private String status;

    private UUID originatingStoreId;

    private UUID fulfillingStoreId;

    private Integer totalAmountCents;

    private OffsetDateTime createdAt;

    private Integer itemsCount;

    private String role;
}
