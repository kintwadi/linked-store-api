package com.vicinity24.core.linkedstore.api.automatic.product.load.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductLoadReport {

    private OffsetDateTime generatedAt;

    private UUID storeId;
    private String storeName;

    private String format; // json | csv | xml

    private int rowsParsed;

    private int productsCreated;
    private int productsUpdated;
    private int productsSkipped;

    private int variantsCreated;
    private int variantsUpdated;
    private int variantsSkipped;

    private int imagesCopied;
    private int imagesSkipped;

    private int errors;

    @Builder.Default
    private List<String> warningMessages = new ArrayList<>();

    @Builder.Default
    private List<RowError> rowErrors = new ArrayList<>();

    @Builder.Default
    private List<String> summaryHighlights = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private Integer row;
        private String title;
        private String sku;
        private String message;
    }
}
