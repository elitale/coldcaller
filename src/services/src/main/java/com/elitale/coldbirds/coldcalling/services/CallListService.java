package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Business operations on lead lists ({@code call_lists}).
 *
 * <p>Owns the list↔lead membership relation (add / remove / live counts) so the
 * {@link LeadService} stays lead-centric. The power dialer continues to read the
 * repository directly; this service drives the Leads-workbench lists rail.
 */
public final class CallListService {

    /** A list paired with its live (non-deleted) lead count for the rail badges. */
    public record ListSummary(CallList list, int leadCount) {
        public ListSummary {
            Objects.requireNonNull(list, "list must not be null");
            if (leadCount < 0) throw new IllegalArgumentException("leadCount must be >= 0");
        }
    }

    private final CallListRepository repo;

    public CallListService(CallListRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
    }

    /** All non-deleted lists, each with its live lead count. Sorted by the repo (by name). */
    public List<ListSummary> listsWithCounts() {
        List<ListSummary> out = new ArrayList<>();
        for (CallList list : repo.findAll()) {
            out.add(new ListSummary(list, repo.countLeads(list.id())));
        }
        return List.copyOf(out);
    }

    /** Create a new, empty list. Rejects a blank name (via the repo DTO). */
    public Result<CallList> create(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) return Result.err("name must not be blank");
        return repo.save(new CallListRepository.NewCallList(name.strip(), Optional.empty()));
    }

    /** Rename a list in place. */
    public Result<CallList> rename(CallListId id, String name) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        return repo.rename(id, name);
    }

    /** Soft-delete a list. Members are detached by the cascade on hard delete; soft keeps rows. */
    public Result<Void> delete(CallListId id) {
        return repo.delete(Objects.requireNonNull(id, "id must not be null"));
    }

    /** Append leads to a list, skipping any already present. Returns the number added. */
    public int addLeads(CallListId listId, List<LeadId> leadIds) {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(leadIds, "leadIds must not be null");
        return repo.addLeads(listId, leadIds);
    }

    /** Remove a single lead from a list. */
    public boolean removeLead(CallListId listId, LeadId leadId) {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(leadId, "leadId must not be null");
        return repo.removeLead(listId, leadId);
    }

    /** Live lead count for one list. */
    public int countLeads(CallListId listId) {
        return repo.countLeads(Objects.requireNonNull(listId, "listId must not be null"));
    }
}
