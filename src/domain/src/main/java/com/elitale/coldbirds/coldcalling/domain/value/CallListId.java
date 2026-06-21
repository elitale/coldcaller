package com.elitale.coldbirds.coldcalling.domain.value;

public record CallListId(long value) {
    public CallListId {
        if (value <= 0) throw new IllegalArgumentException("CallListId must be positive, got: " + value);
    }
}
