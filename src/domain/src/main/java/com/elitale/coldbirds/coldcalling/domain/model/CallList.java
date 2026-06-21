package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An ordered list of contacts used by the power dialer. */
public record CallList(
        CallListId id,
        String name,
        Optional<String> description,
        List<CallListEntry> entries,
        Instant createdAt,
        Instant updatedAt
) {
    public CallList {
        Objects.requireNonNull(id,          "id must not be null");
        Objects.requireNonNull(name,        "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(entries,     "entries must not be null");
        Objects.requireNonNull(createdAt,   "createdAt must not be null");
        Objects.requireNonNull(updatedAt,   "updatedAt must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        entries = List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }
}
