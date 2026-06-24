package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.CallListEntry;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface CallListRepository {

    record NewCallList(
            String name,
            Optional<String> description
    ) {
        public NewCallList {
            Objects.requireNonNull(name,        "name must not be null");
            Objects.requireNonNull(description, "description must not be null");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        }
    }

    Result<CallList> save(NewCallList list);

    Result<CallList> update(CallList list);

    /** Rename a list in place. Rejects a blank name or a missing/deleted list. */
    Result<CallList> rename(CallListId id, String name);

    Optional<CallList> findById(CallListId id);

    /** Returns all non-deleted call lists (without entries for performance). */
    List<CallList> findAll();

    /** Live (non-deleted) lead count for a list — drives the lists-rail badges. */
    int countLeads(CallListId listId);

    /** Soft delete. */
    Result<Void> delete(CallListId id);

    Result<Void> addEntry(CallListId listId, LeadId leadId);

    /**
     * Append leads to a list at tail positions, ignoring leads already present
     * ({@code INSERT OR IGNORE} on the {@code (list_id, lead_id)} unique index).
     *
     * @return the number of memberships actually inserted (duplicates skipped)
     */
    int addLeads(CallListId listId, List<LeadId> leadIds);

    /** Remove a single lead from a list. {@code true} when a membership row was deleted. */
    boolean removeLead(CallListId listId, LeadId leadId);

    Result<Void> updateEntryStatus(long entryId, CallListEntry.DialStatus status);
}
