package com.elitale.coldbirds.coldcalling.domain.value;

import java.time.ZoneId;
import java.util.Objects;

/**
 * A dialable country: ISO 3166-1 alpha-2 code, E.164 dial prefix, and a
 * representative timezone used for local-time display in the dialer. The flag
 * glyph is derived on demand from the ISO code via Unicode regional-indicator
 * symbols ({@link #flag()}). Immutable value object.
 */
public record Country(
        String isoCode,
        String displayName,
        String dialCode,
        String zoneId) {

    public Country {
        Objects.requireNonNull(isoCode, "isoCode must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(dialCode, "dialCode must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        if (isoCode.length() != 2) {
            throw new IllegalArgumentException("isoCode must be ISO 3166-1 alpha-2: " + isoCode);
        }
        if (!dialCode.matches("\\+\\d{1,4}")) {
            throw new IllegalArgumentException("Invalid dial code: " + dialCode);
        }
    }

    /** Resolve the timezone for this country's representative region. */
    public ZoneId zone() {
        return ZoneId.of(zoneId);
    }

    /**
     * The country's flag as a Unicode emoji, derived from its ISO 3166-1
     * alpha-2 code by mapping each letter to its regional-indicator symbol
     * (e.g. {@code "US"} → 🇺🇸). Note: some platforms (including the default
     * JavaFX text renderer) display these as boxed letter pairs.
     */
    public String flag() {
        int base = 0x1F1E6; // REGIONAL INDICATOR SYMBOL LETTER A
        char first = Character.toUpperCase(isoCode.charAt(0));
        char second = Character.toUpperCase(isoCode.charAt(1));
        return new String(Character.toChars(base + (first - 'A')))
                + new String(Character.toChars(base + (second - 'A')));
    }
}
