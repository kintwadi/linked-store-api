package com.vicinity24.core.linkedstore.api.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationIqSearchHit(
        String lat,
        String lon,
        String display_name,
        Map<String, Object> address
) {
}
