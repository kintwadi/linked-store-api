package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedResponse<T> {

    private List<T> content;

    private int page;

    private int size;

    private long totalElements;

    private int totalPages;

    private boolean first;

    private boolean last;

    private boolean empty;

    private List<StoreTotals> storeTotals;

    private List<StatusTotals> statusTotals;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoreTotals {
        private String storeId;
        private String storeName;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusTotals {
        private String key;
        private long count;
    }
}
