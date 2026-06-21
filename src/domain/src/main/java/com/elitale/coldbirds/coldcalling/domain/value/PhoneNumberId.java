package com.elitale.coldbirds.coldcalling.domain.value;

public record PhoneNumberId(long value) {
    public PhoneNumberId {
        if (value <= 0) throw new IllegalArgumentException("PhoneNumberId must be positive, got: " + value);
    }
}
