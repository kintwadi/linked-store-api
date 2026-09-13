package com.vicinity24.core.linkedstore.api.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PaymentProviderFactory {

    public static final String PROVIDER_STRIPE = "STRIPE";

    private final Map<String, PaymentProvider> providers;

    public PaymentProviderFactory(List<PaymentProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(p -> p.providerId().toUpperCase(), Function.identity()));
        log.info("PaymentProviderFactory loaded providers: {}", this.providers.keySet());
        if (!providers.containsKey(PROVIDER_STRIPE)) {
            log.warn("Default provider '{}' not registered. Fallbacks will fail.", PROVIDER_STRIPE);
        }
    }

    public PaymentProvider getProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return defaultProvider();
        }
        PaymentProvider provider = providers.get(providerId.toUpperCase());
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown payment provider '" + providerId + "'. Installed: " + providers.keySet());
        }
        return provider;
    }

    public PaymentProvider defaultProvider() {
        PaymentProvider stripe = providers.get(PROVIDER_STRIPE);
        if (stripe != null) return stripe;
        throw new IllegalStateException(
                "No default payment provider installed. Available: " + providers.keySet());
    }
}
