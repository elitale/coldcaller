package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.CallListEntry;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
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

    Optional<CallList> findById(CallListId id);

    /** Returns all non-deleted call lists (without entries for performance). */
    List<CallList> findAll();

    /** Soft delete. */
    Result<Void> delete(CallListId id);

    Result<Void> addEntry(CallListId listId, ContactId contactId);

    Result<Void> updateEntryStatus(long entryId, CallListEntry.DialStatus status);
}
