package com.elitale.coldbirds.coldcalling.domain.value;

public record CallId(long value) {
    public CallId {
        if (value <= 0) throw new IllegalArgumentException("CallId must be positive, got: " + value);
    }
}
