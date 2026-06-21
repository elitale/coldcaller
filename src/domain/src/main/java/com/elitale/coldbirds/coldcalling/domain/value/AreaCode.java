package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.Objects;

/** 3-digit US/Canada area code extracted from a phone number. */
public record AreaCode(String value) {

    public AreaCode {
        Objects.requireNonNull(value, "AreaCode value must not be null");
        if (!value.matches("\\d{3}")) {
            throw new IllegalArgumentException("AreaCode must be exactly 3 digits, got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
