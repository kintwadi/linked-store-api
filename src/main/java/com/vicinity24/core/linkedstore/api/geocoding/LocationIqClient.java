package com.vicinity24.core.linkedstore.api.geocoding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class LocationIqClient {

    private final RestClient restClient;
    private final LocationIqProperties properties;

    public LocationIqClient(
            RestClient.Builder restClientBuilder,
            LocationIqProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;

        int timeout = properties.timeoutMs() > 0 ? properties.timeoutMs() : 5000;
        String baseUrl = StringUtils.hasText(properties.baseUrl()) ? properties.baseUrl() : "https://eu1.locationiq.com";

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                })
                .build();
    }

    public List<LocationIqSearchHit> forwardGeocode(String freeformQuery, String countryCodesCsv) throws Exception {
        try {
            List<LocationIqSearchHit> result = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/search.php")
                            .queryParam("key", properties.apiKey())
                            .queryParam("q", freeformQuery)
                            .queryParam("format", "json")
                            .queryParam("limit", 3)
                            .queryParam("countrycodes", countryCodesCsv)
                            .queryParam("addressdetails", 1)
                            .queryParam("normalizeaddress", 1)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new RuntimeException("LocationIQ client error: HTTP " + response.getStatusCode().value());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new RuntimeException("LocationIQ server error: HTTP " + response.getStatusCode().value());
                    })
                    .body(new ParameterizedTypeReference<List<LocationIqSearchHit>>() {});
            return result != null ? result : List.of();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call LocationIQ forward geocode API: " + e.getMessage(), e);
        }
    }
}
