package com.vicinity24.core.linkedstore.api.config;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.param.AccountListParams;
import com.vicinity24.core.linkedstore.api.dto.StripeErrorInfo;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class StripeConfig {

    private static final Logger log = LoggerFactory.getLogger(StripeConfig.class);

    @Value("${stripe.api-key}")
    private String stripeApiKey;

    @Value("${stripe.public-key}")
    private String stripePublicKey;

    @Value("${stripe.webhook-secret}")
    private String stripeWebhookSecret;

    @Value("${stripe.platform-fee-percent}")
    private double platformFeePercent;

    @Value("${stripe.subscription.plus-price-id:}")
    private String subscriptionPlusPriceId;

    @Value("${stripe.subscription.pro-price-id:}")
    private String subscriptionProPriceId;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeApiKey;
    }

    /**
     * Runs once after the Spring context is fully up, makes a single cheap
     * Account.list(limit=1) call against the configured STRIPE_SECRET_KEY to
     * confirm the owning Stripe account has Connect platform activated.
     *
     * Stripe Connect activation is a ONE-TIME click in the dashboard:
     *   Test mode:  https://dashboard.stripe.com/test/settings/connect
     *   Live mode:  https://dashboard.stripe.com/settings/connect
     * (or the old URL from the error: https://dashboard.stripe.com/account/applications/settings)
     * Without it, ANY Connect call (Account.retrieve, AccountLink.create,
     * LoginLink.createOnAccount, etc.) fails with Stripe code "platform_account_required"
     * and the message "Only Stripe Connect platforms can work with other accounts."
     *
     * Emitting this at BOOT lets operators see the problem immediately, instead of
     * having to click "Re-onboard" and decode a red toast.
     */
    @Bean
    public CommandLineRunner stripeConnectPlatformVerifier() {
        return args -> {
            String key = stripeApiKey == null ? "" : stripeApiKey.trim();
            if (key.isEmpty() || key.toLowerCase().startsWith("pk_") || key.toLowerCase().contains("replace")) {
                log.error("\n" +
                        "===============================================================================================\n" +
                        "  STRIPE SECRET KEY NOT CONFIGURED PROPERLY.\n" +
                        "  The configured stripe.api-key starts with 'pk_' (public key) or is placeholder.\n" +
                        "  Connect onboarding cannot work until STRIPE_SECRET_KEY (sk_...) is set in setup.bat.\n" +
                        "===============================================================================================\n");
                return;
            }
            final boolean live = key.toLowerCase().startsWith("sk_live");
            final boolean test = key.toLowerCase().startsWith("sk_test");
            final String settingsUrl = live
                    ? "https://dashboard.stripe.com/settings/connect"
                    : "https://dashboard.stripe.com/test/settings/connect";
            try {
                Account.list(AccountListParams.builder().setLimit(1L).build());
                log.info("Stripe Connect platform verified (mode={}). Onboarding/dashboard links will work.",
                        live ? "LIVE" : (test ? "TEST" : "unknown"));
            } catch (StripeException sx) {
                StripeErrorInfo sei = StripeErrorInfo.from(sx);
                if ("platform_account_required".equalsIgnoreCase(sei.code())) {
                    log.error("\n" +
                            "===============================================================================================\n" +
                            "  STRIPE CONNECT PLATFORM HAS NOT BEEN ACTIVATED ON THIS STRIPE ACCOUNT.\n" +
                            "  Secret key mode: {}\n" +
                            "  Stripe error code: platform_account_required\n" +
                            "  {}\n" +
                            "\n" +
                            "  ACTION REQUIRED (one-time, takes 30 seconds):\n" +
                            "    1. Open {}\n" +
                            "       (must be logged into the same Stripe account that owns this secret key)\n" +
                            "    2. Click 'Get started with Connect' (usually top-right).\n" +
                            "    3. Choose 'Platform or marketplace' → 'Managed / Express payouts'.\n" +
                            "    4. Fill the 3 short prompts (business, country, use case).\n" +
                            "  Then restart the backend — the 'Re-onboard' button will now work.\n" +
                            "===============================================================================================\n",
                            live ? "LIVE (sk_live_...)" : (test ? "TEST (sk_test_...)" : "UNKNOWN"),
                            sei.userMessage() != null ? sei.userMessage() : sx.getMessage(),
                            settingsUrl);
                } else {
                    log.warn("Stripe Connect platform check FAILED (non-fatal): code={} msg={}",
                            sei.code(),
                            sei.userMessage() != null ? sei.userMessage() : sx.getMessage());
                }
            } catch (RuntimeException rx) {
                log.warn("Stripe Connect platform check skipped (network/timeout?). Startup continues: {}",
                        rx.getMessage());
            }
        };
    }
}
