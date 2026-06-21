package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.CallId;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface CallRepository {

    record NewCall(
            CallDirection direction,
            PhoneNumberId phoneNumberId,
            Optional<ContactId> contactId,
            PhoneNumber remoteNumber,
            Optional<CallDisposition> disposition,
            Instant startedAt,
            Optional<Instant> answeredAt,
            Optional<Instant> endedAt,
            Optional<Long> durationMs,
            Optional<String> recordingPath,
            Optional<String> notes
    ) {
        public NewCall {
            Objects.requireNonNull(direction,     "direction must not be null");
            Objects.requireNonNull(phoneNumberId, "phoneNumberId must not be null");
            Objects.requireNonNull(contactId,     "contactId must not be null");
            Objects.requireNonNull(remoteNumber,  "remoteNumber must not be null");
            Objects.requireNonNull(disposition,   "disposition must not be null");
            Objects.requireNonNull(startedAt,     "startedAt must not be null");
            Objects.requireNonNull(answeredAt,    "answeredAt must not be null");
            Objects.requireNonNull(endedAt,       "endedAt must not be null");
            Objects.requireNonNull(durationMs,    "durationMs must not be null");
            Objects.requireNonNull(recordingPath, "recordingPath must not be null");
            Objects.requireNonNull(notes,         "notes must not be null");
        }
    }

    Result<Call> save(NewCall call);

    Result<Call> update(Call call);

    Optional<Call> findById(CallId id);

    List<Call> findByContact(ContactId contactId);

    List<Call> findByPhoneNumber(PhoneNumberId phoneNumberId);

    List<Call> findRecent(int limit);
}
