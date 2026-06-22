package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the most likely {@link Country} for an E.164 phone number by
 * longest-matching dial-code prefix. Pure and side-effect free.
 *
 * <p>For shared dial codes (e.g. {@code +1}) the first catalog entry that
 * matches wins — callers needing strict disambiguation must supply a
 * pre-filtered catalog.
 */
public final class CountryLookup {

    private CountryLookup() {}

    /**
     * Find the country whose dial code is the longest prefix of {@code e164Number}.
     *
     * @param catalog    candidate countries; must not be null
     * @param e164Number a phone number in E.164 (or any digit-bearing) form; may be null/blank
     * @return the best matching country, or empty if none matches
     */
    public static Optional<Country> byE164(final List<Country> catalog, final String e164Number) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        if (e164Number == null || e164Number.isBlank()) {
            return Optional.empty();
        }
        final String digits = e164Number.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return Optional.empty();
        }

        Country best = null;
        int bestLength = 0;
        for (final Country country : catalog) {
            final String codeDigits = country.dialCode().replaceAll("[^0-9]", "");
            if (!codeDigits.isEmpty()
                    && digits.startsWith(codeDigits)
                    && codeDigits.length() > bestLength) {
                best = country;
                bestLength = codeDigits.length();
            }
        }
        return Optional.ofNullable(best);
    }
}
