package com.vicinity24.core.linkedstore.subscription;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanTier {

    PRO("Pro Plan", "PRO_PLAN", false),
    CUSTOM("Custom Plan", "CUSTOM_PLAN", true);

    private final String displayName;
    private final String planCode;
    private final boolean isEnterprise;
}
