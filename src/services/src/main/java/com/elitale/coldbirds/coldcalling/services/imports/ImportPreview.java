package com.elitale.coldbirds.coldcalling.services.imports;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link LeadImportService#preview}: every classified row plus the
 * reconciling tallies shown in the wizard's Review step. No writes have occurred.
 */
public record ImportPreview(
        int totalRows,
        List<PreviewRow> rows,
        int validCount,
        int needsReviewCount,
        int duplicateCount,
        int dncCount,
        int emptyCount) {

    public ImportPreview {
        Objects.requireNonNull(rows, "rows must not be null");
        rows = List.copyOf(rows);
    }

    /** Tally the rows into a preview (counts derived from each row's status). */
    public static ImportPreview of(int totalRows, List<PreviewRow> rows) {
        int valid = 0;
        int review = 0;
        int dupe = 0;
        int dnc = 0;
        int empty = 0;
        for (PreviewRow row : rows) {
            switch (row.status()) {
                case VALID -> valid++;
                case NEEDS_REVIEW -> review++;
                case DUPLICATE -> dupe++;
                case DNC -> dnc++;
                case EMPTY -> empty++;
            }
        }
        return new ImportPreview(totalRows, rows, valid, review, dupe, dnc, empty);
    }

    /** Rows that will be written on commit. */
    public List<PreviewRow> dialableRows() {
        return rows.stream().filter(PreviewRow::isDialable).toList();
    }
}
