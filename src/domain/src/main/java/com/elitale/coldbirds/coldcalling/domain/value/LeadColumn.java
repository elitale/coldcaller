package com.elitale.coldbirds.coldcalling.domain.value;

/**
 * A filterable text column on a lead. The storage layer maps each value to its
 * SQL column via an exhaustive switch.
 */
public enum LeadColumn {
    FIRST_NAME,
    LAST_NAME,
    COMPANY,
    TITLE,
    PHONE,
    EMAIL
}
