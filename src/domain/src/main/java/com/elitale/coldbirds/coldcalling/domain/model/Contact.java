package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A person to call or SMS. Immutable entity. */
public record Contact(
        ContactId id,
        Optional<String> firstName,
        Optional<String> lastName,
        PhoneNumber phone,
        Optional<String> company,
        Optional<String> title,
        Optional<String> email,
        List<String> tags,
        Optional<String> notes,
        boolean dnc,
        Instant createdAt,
        Instant updatedAt
) {
    public Contact {
        Objects.requireNonNull(id,        "id must not be null");
        Objects.requireNonNull(phone,     "phone must not be null");
        Objects.requireNonNull(firstName, "firstName must not be null");
        Objects.requireNonNull(lastName,  "lastName must not be null");
        Objects.requireNonNull(company,   "company must not be null");
        Objects.requireNonNull(title,     "title must not be null");
        Objects.requireNonNull(email,     "email must not be null");
        Objects.requireNonNull(tags,      "tags must not be null");
        Objects.requireNonNull(notes,     "notes must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        tags = List.copyOf(tags);
    }

    /** Best display name — full name if present, else phone number. */
    public String displayName() {
        String first = firstName.orElse("");
        String last  = lastName.orElse("");
        String name  = (first + " " + last).trim();
        return name.isEmpty() ? phone.value() : name;
    }
}
