package com.elitale.coldbirds.coldcalling.services.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps each source header to a {@link LeadField} and designates one phone column
 * as the dialer primary (also the dedupe key). Immutable.
 */
public record ColumnMapping(
        List<String> headers,
        Map<String, LeadField> fieldByHeader,
        Optional<String> primaryPhoneHeader) {

    public ColumnMapping {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(fieldByHeader, "fieldByHeader must not be null");
        Objects.requireNonNull(primaryPhoneHeader, "primaryPhoneHeader must not be null");
        headers = List.copyOf(headers);
        fieldByHeader = Map.copyOf(fieldByHeader);
        if (primaryPhoneHeader.isPresent()
                && fieldByHeader.get(primaryPhoneHeader.get()) != LeadField.PHONE) {
            throw new IllegalArgumentException("primary phone header is not mapped to PHONE");
        }
    }

    public LeadField fieldOf(String header) {
        return fieldByHeader.getOrDefault(header, LeadField.CUSTOM);
    }

    /** All headers mapped to PHONE, in original column order. */
    public List<String> phoneHeaders() {
        return headers.stream().filter(h -> fieldByHeader.get(h) == LeadField.PHONE).toList();
    }

    public boolean isPrimaryPhone(String header) {
        return primaryPhoneHeader.map(p -> p.equals(header)).orElse(false);
    }

    /** Returns a copy with a different primary phone header. */
    public ColumnMapping withPrimaryPhone(String header) {
        return new ColumnMapping(headers, fieldByHeader, Optional.of(header));
    }

    /** Returns a copy with one header remapped to a different field. */
    public ColumnMapping withField(String header, LeadField field) {
        final Map<String, LeadField> next = new LinkedHashMap<>(fieldByHeader);
        next.put(header, field);
        final Optional<String> primary =
                primaryPhoneHeader.filter(p -> !p.equals(header) || field == LeadField.PHONE);
        return new ColumnMapping(headers, next, primary);
    }
}
