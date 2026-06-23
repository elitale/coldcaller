package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.Optional;

/**
 * Compound keyset-pagination cursor over {@code (created_at DESC, id DESC)}.
 *
 * <p>The {@code id} tiebreaker is required: bulk imports create thousands of rows
 * sharing one {@code createdAtMillis}, so cursoring on the timestamp alone would
 * skip an entire batch.
 *
 * <p>Wire format: {@code "{createdAtMillis}_{id}"}.
 */
public record Cursor(long createdAtMillis, long id) {

    public Cursor {
        if (id <= 0) {
            throw new IllegalArgumentException("Cursor id must be positive, got: " + id);
        }
    }

    /** Serialize to the wire format {@code "{ms}_{id}"}. */
    public String format() {
        return createdAtMillis + "_" + id;
    }

    /** Parse the wire format. Returns empty for null/blank/malformed input. */
    public static Optional<Cursor> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        int sep = raw.indexOf('_');
        if (sep <= 0 || sep == raw.length() - 1) {
            return Optional.empty();
        }
        try {
            long createdAtMillis = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            if (id <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Cursor(createdAtMillis, id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
