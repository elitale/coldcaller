package com.elitale.coldbirds.coldcalling.domain.value;

public record LeadId(long value) {
    public LeadId {
        if (value <= 0) throw new IllegalArgumentException("LeadId must be positive, got: " + value);
    }
}
