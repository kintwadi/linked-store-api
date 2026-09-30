package com.vicinity24.core.linkedstore.subscription;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "linkedstore.subscription.tiers")
public class SubscriptionTierSettings {

    private TierProSettings pro = new TierProSettings();
    private TierCustomSettings custom = new TierCustomSettings();

    @Data
    public static class TierProSettings {

        private int monthlyPriceCents = 2900;
        private int annualPriceCents = 27600;
        private int trialDays = 30;

        /**
         * Monthly order limit for the PRO tier.
         * Semantics: null means no cap (unlimited); a non-null value must be >= 0
         * and represents the hard quota enforced each calendar month.
         */
        private Integer monthlyOrderLimit = 100;

        /**
         * Maximum connected stores for the PRO tier.
         * Semantics: null means no cap (unlimited); a non-null value must be >= 0
         * and represents the maximum number of stores that may be linked.
         */
        private Integer maxConnectedStores = null;

        private String currency = "usd";
    }

    @Data
    public static class TierCustomSettings {

        private String displayName = "Custom Plan";
        private boolean contactSalesEnabled = true;
    }
}
