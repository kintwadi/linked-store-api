package com.vicinity24.core.linkedstore.api.geocoding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeocodingService {

    private final LocationIqClient locationIqClient;
    private final LocationIqProperties properties;

    public GeocodingService(LocationIqClient locationIqClient, LocationIqProperties properties) {
        this.locationIqClient = locationIqClient;
        this.properties = properties;
    }

    public GeocodeResult resolveStoreLocation(String address, String postalCode, String countryCode) {
        try {
            if (!StringUtils.hasText(properties.apiKey())) {
                log.info("GeocodingService: LocationIQ API key not configured; skipping forward geocode (setup.bat LOCATION_IQ_API_KEY is empty or not exported)");
                return new GeocodeResult(false, null, null, null, null, null,
                        "LocationIQ API key not configured");
            }

            boolean addressBlank = !StringUtils.hasText(address);
            boolean postalCodeBlank = !StringUtils.hasText(postalCode);

            if (addressBlank && postalCodeBlank) {
                log.warn("Geocoding failed: No address or postal code provided");
                return new GeocodeResult(false, null, null, null, null, null,
                        "No address or postal code provided");
            }

            StringBuilder queryBuilder = new StringBuilder();
            if (StringUtils.hasText(address)) {
                queryBuilder.append(address.trim());
            }
            if (StringUtils.hasText(postalCode)) {
                if (!queryBuilder.isEmpty()) {
                    queryBuilder.append(", ");
                }
                queryBuilder.append(postalCode.trim());
            }
            if (StringUtils.hasText(countryCode)) {
                if (!queryBuilder.isEmpty()) {
                    queryBuilder.append(", ");
                }
                queryBuilder.append(countryCode.trim());
            }
            String freeformQuery = queryBuilder.toString();

            String countryCodesParam;
            if (StringUtils.hasText(countryCode)) {
                countryCodesParam = countryCode.toLowerCase();
            } else {
                String configured = properties.countrycodes();
                countryCodesParam = StringUtils.hasText(configured) ? configured : "pt,de,fr,be";
            }

            List<LocationIqSearchHit> results = locationIqClient.forwardGeocode(freeformQuery, countryCodesParam);

            if (results == null || results.isEmpty()) {
                log.warn("Geocoding failed: LocationIQ returned 0 matches for query '{}'", freeformQuery);
                return new GeocodeResult(false, null, null, null, null, null,
                        "LocationIQ returned 0 matches for query");
            }

            LocationIqSearchHit hit = results.get(0);
            BigDecimal latitude = new BigDecimal(hit.lat()).setScale(6, RoundingMode.HALF_UP);
            BigDecimal longitude = new BigDecimal(hit.lon()).setScale(6, RoundingMode.HALF_UP);

            String matchedAddress = hit.display_name();
            String matchedPostalCode = null;
            String matchedCountryCode = null;

            Map<String, Object> addr = hit.address();
            if (addr != null) {
                Object countryCodeObj = addr.get("country_code");
                if (countryCodeObj != null) {
                    matchedCountryCode = countryCodeObj.toString().toUpperCase();
                }
                Object postcodeObj = addr.get("postcode");
                if (postcodeObj != null) {
                    matchedPostalCode = postcodeObj.toString();
                }
            }

            log.info("Geocoding success: lat={}, lon={}, address='{}'", latitude, longitude, matchedAddress);
            return new GeocodeResult(true, latitude, longitude, matchedAddress, matchedPostalCode, matchedCountryCode, null);

        } catch (Exception e) {
            log.warn("Geocoding failed with exception: {}", e.getMessage());
            return new GeocodeResult(false, null, null, null, null, null, e.getMessage());
        }
    }
}
