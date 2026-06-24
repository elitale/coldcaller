package com.elitale.coldbirds.coldcalling.services.imports;

/**
 * The lead field a CSV column maps to. {@code PHONE} columns are normalized;
 * the designated primary becomes the dialer number + dedupe key while the rest
 * are preserved as custom fields. {@code CUSTOM} preserves an unmapped column
 * under its header; {@code IGNORE} drops it.
 */
public enum LeadField {
    FIRST_NAME,
    LAST_NAME,
    COMPANY,
    TITLE,
    EMAIL,
    PHONE,
    CUSTOM,
    IGNORE
}
