package com.elitale.coldbirds.coldcalling.domain.value;

public record SmsId(long value) {
    public SmsId {
        if (value <= 0) throw new IllegalArgumentException("SmsId must be positive, got: " + value);
    }
}
