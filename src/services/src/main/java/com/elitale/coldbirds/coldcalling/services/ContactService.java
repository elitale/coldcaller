package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Business operations on {@link Contact} entities.
 * <p>
 * Wraps {@link ContactRepository} with DNC checking and query convenience methods.
 * Never returns {@code null} from any public method.
 */
public final class ContactService {

    /**
     * Service-layer DTO for creating a new contact.
     * <p>
     * Defined here (not in {@code storage}) so that the UI layer can create
     * contacts without depending on the storage module.
     */
    public record NewContact(
            Optional<String> firstName,
            Optional<String> lastName,
            PhoneNumber phone,
            Optional<String> company,
            Optional<String> title,
            Optional<String> email,
            List<String> tags,
            Optional<String> notes
    ) {
        public NewContact {
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

    private static final Logger LOG = LoggerFactory.getLogger(ContactService.class);

    private final ContactRepository repo;

    public ContactService(ContactRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
    }

    /** Return all non-deleted contacts. */
    public List<Contact> findAll() {
        return repo.findAll();
    }

    /** Full-text search across name, company, phone. */
    public List<Contact> search(String query) {
        Objects.requireNonNull(query, "query must not be null");
        return repo.search(query);
    }

    /** Return a contact by phone number, if any. */
    public Optional<Contact> findByPhone(PhoneNumber phone) {
        return repo.findByPhone(Objects.requireNonNull(phone));
    }

    /** Return a contact by ID, if found. */
    public Optional<Contact> findById(ContactId id) {
        return repo.findById(Objects.requireNonNull(id));
    }

    /**
     * Return {@code true} if the number is on the Do-Not-Call list.
     * Unknown numbers return {@code false}.
     */
    public boolean isDnc(PhoneNumber phone) {
        return repo.findByPhone(Objects.requireNonNull(phone))
                .map(Contact::dnc)
                .orElse(false);
    }

    /** Persist a new contact. Returns the saved contact or an error. */
    public Result<Contact> save(NewContact contact) {
        Objects.requireNonNull(contact, "contact must not be null");
        return repo.save(new ContactRepository.NewContact(
                contact.firstName(),
                contact.lastName(),
                contact.phone(),
                contact.company(),
                contact.title(),
                contact.email(),
                contact.tags(),
                contact.notes()
        ));
    }

    /** Update an existing contact. */
    public Result<Contact> update(Contact contact) {
        return repo.update(Objects.requireNonNull(contact));
    }

    /** Soft-delete a contact by ID. */
    public Result<Void> delete(ContactId id) {
        return repo.delete(Objects.requireNonNull(id));
    }
}
