package com.elitale.coldbirds.coldcalling.services.imports;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link LeadImportService#commit}. The reconciling equation holds:
 * {@code totalRows == created + updated + skippedDuplicate + skippedInvalid +
 * skippedDnc + skippedEmpty + errors}.
 */
public record ImportResult(
        String batchId,
        int totalRows,
        int created,
        int updated,
        int skippedDuplicate,
        int skippedInvalid,
        int skippedDnc,
        int skippedEmpty,
        int errors,
        List<ErrorRow> errorRows) {

    public ImportResult {
        Objects.requireNonNull(batchId, "batchId must not be null");
        Objects.requireNonNull(errorRows, "errorRows must not be null");
        errorRows = List.copyOf(errorRows);
    }

    /** A row that failed to import, for the downloadable error report. */
    public record ErrorRow(int sourceLine, String reason, List<String> rawValues) {
        public ErrorRow {
            Objects.requireNonNull(reason, "reason must not be null");
            rawValues = List.copyOf(rawValues);
        }
    }
}
