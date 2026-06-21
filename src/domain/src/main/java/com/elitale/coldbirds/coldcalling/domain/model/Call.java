package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.CallId;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A single call event — inbound or outbound. */
public record Call(
        CallId id,
        CallDirection direction,
        PhoneNumberId phoneNumberId,
        Optional<ContactId> contactId,
        PhoneNumber remoteNumber,
        Optional<CallDisposition> disposition,
        Instant startedAt,
        Optional<Instant> answeredAt,
        Optional<Instant> endedAt,
        Optional<Long> durationMs,
        Optional<String> recordingPath,
        Optional<String> notes,
        Instant createdAt,
        Instant updatedAt
) {
    public Call {
        Objects.requireNonNull(id,            "id must not be null");
        Objects.requireNonNull(direction,     "direction must not be null");
        Objects.requireNonNull(phoneNumberId, "phoneNumberId must not be null");
        Objects.requireNonNull(contactId,     "contactId must not be null");
        Objects.requireNonNull(remoteNumber,  "remoteNumber must not be null");
        Objects.requireNonNull(disposition,   "disposition must not be null");
        Objects.requireNonNull(startedAt,     "startedAt must not be null");
        Objects.requireNonNull(answeredAt,    "answeredAt must not be null");
        Objects.requireNonNull(endedAt,       "endedAt must not be null");
        Objects.requireNonNull(durationMs,    "durationMs must not be null");
        Objects.requireNonNull(recordingPath, "recordingPath must not be null");
        Objects.requireNonNull(notes,         "notes must not be null");
        Objects.requireNonNull(createdAt,     "createdAt must not be null");
        Objects.requireNonNull(updatedAt,     "updatedAt must not be null");
    }
}
