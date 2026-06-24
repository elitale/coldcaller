package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;

import java.util.Optional;

/**
 * Lenient phone parsing for inline add / clipboard paste: strips common formatting
 * (spaces, dashes, parens, dots, slashes) then enforces the strict E.164 invariant of
 * {@link PhoneNumber}. Returns empty when the cleaned value is not valid E.164.
 *
 * <p>Headless and unit-tested. The CSV import path uses the richer {@code PhoneNormalizer}
 * (libphonenumber); this is the lightweight in-grid validator.
 */
public final class LeadPhoneParser {

    private LeadPhoneParser() {
    }

    public static Optional<PhoneNumber> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = raw.strip().replaceAll("[\\s\\-()./]", "");
        try {
            return Optional.of(new PhoneNumber(cleaned));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
