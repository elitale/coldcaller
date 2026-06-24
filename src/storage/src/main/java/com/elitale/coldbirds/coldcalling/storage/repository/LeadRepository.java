package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Page;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface LeadRepository {

    record NewLead(
            Optional<String> firstName,
            Optional<String> lastName,
            PhoneNumber phone,
            Optional<String> company,
            Optional<String> title,
            Optional<String> email,
            List<String> tags,
            Optional<String> notes
    ) {
        public NewLead {
            Objects.requireNonNull(firstName, "firstName must not be null");
            Objects.requireNonNull(lastName,  "lastName must not be null");
            Objects.requireNonNull(phone,     "phone must not be null");
            Objects.requireNonNull(company,   "company must not be null");
            Objects.requireNonNull(title,     "title must not be null");
            Objects.requireNonNull(email,     "email must not be null");
            Objects.requireNonNull(tags,      "tags must not be null");
            Objects.requireNonNull(notes,     "notes must not be null");
            tags = List.copyOf(tags);
        }
    }

    Result<Lead> save(NewLead lead);

    Result<Lead> update(Lead lead);

    Optional<Lead> findById(LeadId id);

    Optional<Lead> findByPhone(PhoneNumber phone);

    /** Returns all non-deleted leads. */
    List<Lead> findAll();

    /** Full-text search across first/last name, company, phone. */
    List<Lead> search(String query);

    /**
     * Server-side filtered, keyset-paginated lookup.
     * Filters AND together; ordering is fixed {@code (created_at DESC, id DESC)}.
     */
    Page<Lead> findPage(LeadFilter filter);

    /**
     * Distinct custom-field keys across live leads (optionally scoped to a list),
     * used to render dynamic columns and the custom-field filter.
     */
    List<String> customFieldKeys(Optional<CallListId> listId);

    /** Distinct tags across live leads — source for the tag filter facet. */
    List<String> distinctTags();

    /** Soft delete — sets deleted_at. */
    Result<Void> delete(LeadId id);

    /**
     * Soft-delete many leads at once. Already-deleted ids are ignored.
     *
     * @return the number of leads actually soft-deleted
     */
    int bulkSoftDelete(List<LeadId> ids);

    /** Set the lifecycle status on many leads at once. Returns the number updated. */
    int bulkSetStatus(List<LeadId> ids, com.elitale.coldbirds.coldcalling.domain.value.LeadStatus status);

    /** Set the DNC flag on many leads at once. Returns the number updated. */
    int bulkSetDnc(List<LeadId> ids, boolean dnc);
}
