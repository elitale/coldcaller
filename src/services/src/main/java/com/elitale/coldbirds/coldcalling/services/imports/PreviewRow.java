package com.elitale.coldbirds.coldcalling.services.imports;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One source row resolved against the mapping and phone-normalized during preview.
 * Carries everything needed to either commit (when {@link ImportRowStatus#VALID})
 * or render in the review tray / error report.
 */
public record PreviewRow(
        int sourceLine,
        List<String> rawValues,
        ImportRowStatus status,
        Optional<String> reason,
        Optional<PhoneNumber> primaryPhone,
        boolean assumedCountry,
        Optional<String> firstName,
        Optional<String> lastName,
        Optional<String> company,
        Optional<String> title,
        Optional<String> email,
        Map<String, String> customFields) {

    public PreviewRow {
        Objects.requireNonNull(rawValues, "rawValues must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(primaryPhone, "primaryPhone must not be null");
        Objects.requireNonNull(firstName, "firstName must not be null");
        Objects.requireNonNull(lastName, "lastName must not be null");
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(customFields, "customFields must not be null");
        rawValues = List.copyOf(rawValues);
        customFields = Map.copyOf(customFields);
    }

    public boolean isDialable() {
        return status == ImportRowStatus.VALID;
    }

    /** Copy with a re-classified status/reason (second-pass dup/DNC marking). */
    public PreviewRow withStatus(ImportRowStatus newStatus, Optional<String> newReason) {
        return new PreviewRow(sourceLine, rawValues, newStatus, newReason, primaryPhone,
                assumedCountry, firstName, lastName, company, title, email, customFields);
    }
}
