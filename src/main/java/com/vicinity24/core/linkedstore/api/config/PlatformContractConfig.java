package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.entity.PlatformContractMode;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Configuration
public class PlatformContractConfig {

    @Value("${fee.based.contract:disabled}")
    private String feeBasedContract;

    @Value("${fee.based.contract.percentage:10}")
    private double feeBasedContractPercentage;

    @Value("${subscription.fee.based.contract:disabled}")
    private String subscriptionFeeBasedContract;

    private PlatformContractMode resolvedMode;
    private double resolvedFeePercentage;

    @PostConstruct
    public void init() {
        boolean feeEnabled = isEnabled(feeBasedContract);
        boolean subEnabled = isEnabled(subscriptionFeeBasedContract);

        if (feeEnabled && subEnabled) {
            log.error("PLATFORM CONTRACT MISCONFIGURATION: both FEE_BASED_CONTRACT and SUBSCRIPTION_FEE_BASED_CONTRACT " +
                    "are enabled. Falling back to FEE_BASED. Please disable one of them.");
            resolvedMode = PlatformContractMode.FEE_BASED;
        } else if (feeEnabled) {
            resolvedMode = PlatformContractMode.FEE_BASED;
        } else if (subEnabled) {
            resolvedMode = PlatformContractMode.SUBSCRIPTION;
        } else {
            resolvedMode = PlatformContractMode.NONE;
        }

        resolvedFeePercentage = feeBasedContractPercentage;
        if (resolvedFeePercentage < 0) {
            resolvedFeePercentage = 0;
        } else if (resolvedFeePercentage > 100) {
            resolvedFeePercentage = 100;
        }

        log.info("Platform contract default (from env): mode={}, feePercentage={}%",
                resolvedMode, resolvedFeePercentage);
    }

    private static boolean isEnabled(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase();
        return v.equals("enabled") || v.equals("true") || v.equals("1") || v.equals("yes");
    }
}
