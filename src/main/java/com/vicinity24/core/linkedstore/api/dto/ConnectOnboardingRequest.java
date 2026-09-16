package com.vicinity24.core.linkedstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectOnboardingRequest {

    @NotNull(message = "storeId is required")
    private UUID storeId;

    @NotBlank(message = "refreshUrl is required")
    private String refreshUrl;

    @NotBlank(message = "returnUrl is required")
    private String returnUrl;

    private String ownerEmail;
    private String ownerFullName;
    private String businessType;

    @Pattern(regexp = "^[A-Z]{2}$", message = "country must be a 2-letter ISO 3166-1 alpha-2 code (e.g. DE, US, GB)")
    private String country;

    @Pattern(regexp = "^[A-Z]{3}$", message = "defaultCurrency must be a 3-letter ISO 4217 code (e.g. EUR, USD, GBP)")
    private String defaultCurrency;

    // -- country/currency defaults + Stripe Express supported list (single source of truth) -------

    /** Countries currently supported by Stripe Express Connect + card_payments + transfers capabilities. */
    private static final Set<String> SUPPORTED_COUNTRIES = Set.of(
            "US", "CA", "AU", "NZ", "GB",
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE",
            "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "NO", "PL",
            "PT", "RO", "SK", "SI", "ES", "SE", "CH", "IS", "LI"
    );

    /** Country → default presentment/payout currency mapping (official ISO 4217). */
    private static final Map<String, String> COUNTRY_CURRENCY = Map.ofEntries(
            Map.entry("US", "USD"),
            Map.entry("CA", "CAD"),
            Map.entry("AU", "AUD"),
            Map.entry("NZ", "NZD"),
            Map.entry("GB", "GBP"),
            Map.entry("CH", "CHF"),
            Map.entry("DK", "DKK"),
            Map.entry("NO", "NOK"),
            Map.entry("SE", "SEK"),
            Map.entry("PL", "PLN"),
            Map.entry("CZ", "CZK"),
            Map.entry("HU", "HUF"),
            Map.entry("RO", "RON"),
            Map.entry("BG", "BGN"),
            Map.entry("HR", "HRK")
    );

    public String resolvedCountry() {
        String c = (country != null) ? country.trim().toUpperCase(Locale.ROOT) : "US";
        return SUPPORTED_COUNTRIES.contains(c) ? c : "US";
    }

    public String resolvedDefaultCurrency() {
        if (defaultCurrency != null && !defaultCurrency.isBlank()) {
            String c = defaultCurrency.trim().toUpperCase(Locale.ROOT);
            if (c.length() == 3) return c;
        }
        String cc = resolvedCountry();
        return COUNTRY_CURRENCY.getOrDefault(cc, "EUR");
    }
}

