package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
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

    /** Soft delete — sets deleted_at. */
    Result<Void> delete(LeadId id);
}
