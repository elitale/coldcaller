package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of keyset-paginated results.
 *
 * @param rows       the page's rows (unmodifiable)
 * @param nextCursor cursor to fetch the next page, or empty when exhausted
 * @param total      total rows matching the filter, ignoring the cursor
 * @param <T>        the row type
 */
public record Page<T>(List<T> rows, Optional<Cursor> nextCursor, int total) {

    public Page {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(nextCursor, "nextCursor must not be null");
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative, got: " + total);
        }
        rows = List.copyOf(rows);
    }

    /** An empty page: no rows, no next cursor, zero total. */
    public static <T> Page<T> empty() {
        return new Page<>(List.of(), Optional.empty(), 0);
    }

    /** Whether another page can be fetched. */
    public boolean hasNext() {
        return nextCursor.isPresent();
    }
}
