package com.elitale.coldbirds.coldcalling.storage.repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Records each CSV import commit (for the reconciling summary and Undo) and
 * supports reverting a batch by soft-deleting the leads it created.
 */
public interface ImportBatchRepository {

    /** A persisted import summary row. */
    record ImportBatch(
            String id,
            String fileName,
            Optional<String> defaultCountry,
            int created,
            int updated,
            int skipped,
            int errors,
            Instant createdAt
    ) {
        public ImportBatch {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(defaultCountry, "defaultCountry must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /** Persist a batch summary. The id is also stamped onto the imported leads. */
    void record(ImportBatch batch);

    /**
     * Undo a batch: soft-delete the leads it created and detach their list
     * memberships. Updates to pre-existing leads are not reverted (v1).
     *
     * @return the number of leads soft-deleted
     */
    int undo(String batchId);

    /** Most recent batches, newest first. */
    List<ImportBatch> recentBatches(int limit);
}
