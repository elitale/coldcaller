package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Business operations on {@link Lead} entities.
 * <p>
 * Wraps {@link LeadRepository} with DNC checking and query convenience methods.
 * Never returns {@code null} from any public method.
 */
public final class LeadService {

    /**
     * Service-layer DTO for creating a new lead.
     * <p>
     * Defined here (not in {@code storage}) so that the UI layer can create
     * leads without depending on the storage module.
     */
    public record NewLead(
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

    private static final Logger LOG = LoggerFactory.getLogger(LeadService.class);

    private final LeadRepository repo;

    public LeadService(LeadRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
    }

    /** Return all non-deleted leads. */
    public List<Lead> findAll() {
        return repo.findAll();
    }

    /** Full-text search across name, company, phone. */
    public List<Lead> search(String query) {
        Objects.requireNonNull(query, "query must not be null");
        return repo.search(query);
    }

    /** Return a lead by phone number, if any. */
    public Optional<Lead> findByPhone(PhoneNumber phone) {
        return repo.findByPhone(Objects.requireNonNull(phone));
    }

    /** Return a lead by ID, if found. */
    public Optional<Lead> findById(LeadId id) {
        return repo.findById(Objects.requireNonNull(id));
    }

    /**
     * Return {@code true} if the number is on the Do-Not-Call list.
     * Unknown numbers return {@code false}.
     */
    public boolean isDnc(PhoneNumber phone) {
        return repo.findByPhone(Objects.requireNonNull(phone))
                .map(Lead::dnc)
                .orElse(false);
    }

    /** Persist a new lead. Returns the saved lead or an error. */
    public Result<Lead> save(NewLead lead) {
        Objects.requireNonNull(lead, "lead must not be null");
        return repo.save(new LeadRepository.NewLead(
                lead.firstName(),
                lead.lastName(),
                lead.phone(),
                lead.company(),
                lead.title(),
                lead.email(),
                lead.tags(),
                lead.notes()
        ));
    }

    /** Update an existing lead. */
    public Result<Lead> update(Lead lead) {
        return repo.update(Objects.requireNonNull(lead));
    }

    /** Soft-delete a lead by ID. */
    public Result<Void> delete(LeadId id) {
        return repo.delete(Objects.requireNonNull(id));
    }
}
