package com.vicinity24.core.linkedstore.api.geocoding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linkedstore.geocoding.locationiq")
public record LocationIqProperties(
        String apiKey,
        String baseUrl,
        String countrycodes,
        int timeoutMs
) {
}
