package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import java.util.Objects;

/** A single entry in a CallList: contact reference + position + dial status. */
public record CallListEntry(
        long entryId,
        ContactId contactId,
        int position,
        DialStatus status
) {
    public CallListEntry {
        Objects.requireNonNull(contactId, "contactId must not be null");
        Objects.requireNonNull(status,    "status must not be null");
        if (position < 0) throw new IllegalArgumentException("position must be >= 0");
    }

    public enum DialStatus {
        PENDING,
        DIALED,
        SKIPPED
    }
}
