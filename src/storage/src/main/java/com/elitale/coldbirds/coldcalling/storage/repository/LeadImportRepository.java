package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Write-path repository for the CSV import pipeline. Kept separate from the
 * lead CRUD repository so the non-destructive bulk upsert and the DNC scrub
 * live in one focused unit (SRP) rather than bloating {@code LeadRepository}.
 */
public interface LeadImportRepository {

    /** One de-duplicated, normalized import row destined for the upsert. */
    record UpsertRow(
            Optional<String> firstName,
            Optional<String> lastName,
            PhoneNumber phone,
            Optional<String> company,
            Optional<String> title,
            Optional<String> email,
            List<String> tags,
            Map<String, String> customFields,
            LeadStatus leadStatus
    ) {
        public UpsertRow {
            Objects.requireNonNull(firstName, "firstName must not be null");
            Objects.requireNonNull(lastName, "lastName must not be null");
            Objects.requireNonNull(phone, "phone must not be null");
            Objects.requireNonNull(company, "company must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(customFields, "customFields must not be null");
            Objects.requireNonNull(leadStatus, "leadStatus must not be null");
            tags = List.copyOf(tags);
            customFields = Map.copyOf(customFields);
        }
    }

    /** Outcome of a bulk upsert: how many leads were created vs. updated in place. */
    record UpsertResult(int created, int updated) {}

    /**
     * Non-destructive upsert keyed on the LIVE-phone unique index. Blank cells
     * (bound as NULL) never overwrite existing values; notes / disposition /
     * call history are never touched. Each created lead is stamped with
     * {@code importBatchId}. The caller must pass rows already de-duplicated by
     * primary phone.
     */
    UpsertResult bulkUpsert(List<UpsertRow> rows, String importBatchId);

    /** Returns the subset of the given phones that are flagged DNC on a live lead. */
    Set<String> findDncPhones(Set<String> phones);

    /** Resolve live lead ids for the given primary phones (for list attachment after commit). */
    List<LeadId> findLiveIdsByPhones(Set<String> phones);
}
