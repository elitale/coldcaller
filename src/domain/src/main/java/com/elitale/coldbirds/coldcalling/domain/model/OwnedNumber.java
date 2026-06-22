package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A Twilio phone number purchased and owned by the user. */
public record OwnedNumber(
        PhoneNumberId id,
        PhoneNumber number,
        Optional<String> friendlyName,
        AreaCode areaCode,
        String provider,
        NumberReputation reputation,
        int dailyCalls,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public OwnedNumber {
        Objects.requireNonNull(id,           "id must not be null");
        Objects.requireNonNull(number,       "number must not be null");
        Objects.requireNonNull(friendlyName, "friendlyName must not be null");
        Objects.requireNonNull(areaCode,     "areaCode must not be null");
        Objects.requireNonNull(provider,     "provider must not be null");
        Objects.requireNonNull(reputation,   "reputation must not be null");
        Objects.requireNonNull(createdAt,    "createdAt must not be null");
        Objects.requireNonNull(updatedAt,    "updatedAt must not be null");
        if (dailyCalls < 0) throw new IllegalArgumentException("dailyCalls must be >= 0");
    }
}
