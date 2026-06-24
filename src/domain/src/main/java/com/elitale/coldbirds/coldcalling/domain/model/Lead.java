package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A person to call or SMS. Immutable entity. */
public record Lead(
        LeadId id,
        Optional<String> firstName,
        Optional<String> lastName,
        PhoneNumber phone,
        Optional<String> company,
        Optional<String> title,
        Optional<String> email,
        List<String> tags,
        Optional<String> notes,
        boolean dnc,
        Map<String, String> customFields,
        LeadStatus leadStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public Lead {
        Objects.requireNonNull(id,           "id must not be null");
        Objects.requireNonNull(phone,        "phone must not be null");
        Objects.requireNonNull(firstName,    "firstName must not be null");
        Objects.requireNonNull(lastName,     "lastName must not be null");
        Objects.requireNonNull(company,      "company must not be null");
        Objects.requireNonNull(title,        "title must not be null");
        Objects.requireNonNull(email,        "email must not be null");
        Objects.requireNonNull(tags,         "tags must not be null");
        Objects.requireNonNull(notes,        "notes must not be null");
        Objects.requireNonNull(customFields, "customFields must not be null");
        Objects.requireNonNull(leadStatus,   "leadStatus must not be null");
        Objects.requireNonNull(createdAt,    "createdAt must not be null");
        Objects.requireNonNull(updatedAt,    "updatedAt must not be null");
        tags = List.copyOf(tags);
        customFields = Map.copyOf(customFields);
    }

    /** Best display name — full name if present, else phone number. */
    public String displayName() {
        String first = firstName.orElse("");
        String last  = lastName.orElse("");
        String name  = (first + " " + last).trim();
        return name.isEmpty() ? phone.value() : name;
    }

    /**
     * Returns a copy with a single custom field set. A {@code null} or blank value
     * removes the key. The original is unchanged.
     */
    public Lead withCustomField(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        java.util.LinkedHashMap<String, String> next = new java.util.LinkedHashMap<>(customFields);
        if (value == null || value.isBlank()) {
            next.remove(key);
        } else {
            next.put(key, value);
        }
        return new Lead(id, firstName, lastName, phone, company, title, email, tags, notes,
                dnc, next, leadStatus, createdAt, updatedAt);
    }
}
