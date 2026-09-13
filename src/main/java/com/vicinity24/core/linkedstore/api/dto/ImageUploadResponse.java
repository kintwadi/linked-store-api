package com.vicinity24.core.linkedstore.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageUploadResponse {

    private String key;
    private String publicUrl;
    private String contentType;
    private Long sizeBytes;
    private OffsetDateTime uploadedAt;
    private String scope;
    private String parentId;
}
