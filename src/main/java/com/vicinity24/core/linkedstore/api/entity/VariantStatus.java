package com.vicinity24.core.linkedstore.api.entity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum VariantStatus {
    ACTIVE,
    DRAFT,
    PENDING,
    OUT_OF_STOCK,
    INACTIVE,
    ARCHIVED,
    DISABLED;

    public static final List<String> ALL_NON_INACTIVE = Collections.unmodifiableList(
            Arrays.stream(values())
                    .filter(s -> s != INACTIVE && s != DISABLED)
                    .map(Enum::name)
                    .toList());
}
