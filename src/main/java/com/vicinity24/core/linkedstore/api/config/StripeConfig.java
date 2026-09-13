package com.vicinity24.core.linkedstore.api.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class StripeConfig {

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
}
