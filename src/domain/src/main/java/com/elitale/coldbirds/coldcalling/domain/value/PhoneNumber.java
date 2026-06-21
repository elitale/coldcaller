package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.Objects;

/** E.164-formatted phone number. Immutable value object. */
public record PhoneNumber(String value) {

    public PhoneNumber {
        Objects.requireNonNull(value, "PhoneNumber value must not be null");
        if (!value.matches("\\+[1-9]\\d{1,14}")) {
            throw new IllegalArgumentException("Not a valid E.164 number: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
