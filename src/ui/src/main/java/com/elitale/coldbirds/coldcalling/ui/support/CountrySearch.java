package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pure, side-effect-free matching for the dialer's country search box. A
 * country matches a query when the query (case-insensitive, trimmed) is a
 * substring of its display name, dial code, or ISO code. A blank query matches
 * everything. Kept separate from the JavaFX controller so it is unit-testable.
 */
public final class CountrySearch {

    private CountrySearch() {}

    /** True if {@code country} matches {@code query}. Blank queries match all. */
    public static boolean matches(Country country, String query) {
        Objects.requireNonNull(country, "country must not be null");
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT).strip();
        return country.displayName().toLowerCase(Locale.ROOT).contains(q)
                || country.dialCode().toLowerCase(Locale.ROOT).contains(q)
                || country.isoCode().toLowerCase(Locale.ROOT).contains(q);
    }

    /** All countries matching {@code query}, preserving input order. */
    public static List<Country> filter(List<Country> countries, String query) {
        Objects.requireNonNull(countries, "countries must not be null");
        return countries.stream()
                .filter(country -> matches(country, query))
                .collect(Collectors.toUnmodifiableList());
    }
}
