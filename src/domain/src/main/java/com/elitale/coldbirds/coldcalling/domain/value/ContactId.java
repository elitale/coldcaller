package com.elitale.coldbirds.coldcalling.domain.value;

public record ContactId(long value) {
    public ContactId {
        if (value <= 0) throw new IllegalArgumentException("ContactId must be positive, got: " + value);
    }
}
