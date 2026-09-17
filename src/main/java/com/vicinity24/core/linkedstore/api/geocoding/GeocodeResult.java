package com.vicinity24.core.linkedstore.api.geocoding;
import java.math.BigDecimal;
public record GeocodeResult(
        boolean success,
        BigDecimal latitude,
        BigDecimal longitude,
        String matchedAddress,
        String matchedPostalCode,
        String matchedCountryCode,
        String errorMessage
) {
}
