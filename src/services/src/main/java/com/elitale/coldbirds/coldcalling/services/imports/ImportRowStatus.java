package com.elitale.coldbirds.coldcalling.services.imports;

/** Classification of one source row during import preview. */
public enum ImportRowStatus {
    /** Normalized cleanly — will be created or updated. */
    VALID,
    /** Phone could not be normalized safely — goes to the review tray. */
    NEEDS_REVIEW,
    /** Primary phone duplicates an earlier row in the same file. */
    DUPLICATE,
    /** Primary phone is on the DNC list — excluded from dialable. */
    DNC,
    /** No phone present at all — skipped. */
    EMPTY
}
