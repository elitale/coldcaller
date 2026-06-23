package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.Objects;

/**
 * Lifecycle status of a lead — the calling analog of an email-deliverability status.
 *
 * <p>Used only as a filtering / display facet. {@code DNC} here is a visible label;
 * the authoritative pre-dial block remains the {@code Lead.dnc} boolean enforced
 * service-side.
 *
 * <p>The persisted {@link #dbValue()} is decoupled from {@link #name()} so renaming
 * a Java constant can never silently corrupt the on-disk contract.
 */
public enum LeadStatus {
    NEW("new"),
    CONTACTED("contacted"),
    INTERESTED("interested"),
    CALLBACK("callback"),
    NOT_INTERESTED("not_interested"),
    BAD_NUMBER("bad_number"),
    DNC("dnc");

    private final String dbValue;

    LeadStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The stable persisted string for this status. */
    public String dbValue() {
        return dbValue;
    }

    /** Parse a persisted value back into a status. Throws on unknown input. */
    public static LeadStatus fromDb(String value) {
        Objects.requireNonNull(value, "value must not be null");
        for (LeadStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown lead_status: " + value);
    }
}
