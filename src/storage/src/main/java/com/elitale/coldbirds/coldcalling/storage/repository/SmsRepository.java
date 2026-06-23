package com.elitale.coldbirds.coldcalling.storage.repository;

import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.domain.value.SmsId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface SmsRepository {

    record NewSmsMessage(
            CallDirection direction,
            PhoneNumberId phoneNumberId,
            Optional<LeadId> leadId,
            PhoneNumber remoteNumber,
            String body,
            SmsStatus status,
            Instant sentAt
    ) {
        public NewSmsMessage {
            Objects.requireNonNull(direction,     "direction must not be null");
            Objects.requireNonNull(phoneNumberId, "phoneNumberId must not be null");
            Objects.requireNonNull(leadId,        "leadId must not be null");
            Objects.requireNonNull(remoteNumber,  "remoteNumber must not be null");
            Objects.requireNonNull(body,          "body must not be null");
            Objects.requireNonNull(status,        "status must not be null");
            Objects.requireNonNull(sentAt,        "sentAt must not be null");
            if (body.isBlank()) throw new IllegalArgumentException("body must not be blank");
        }
    }

    Result<SmsMessage> save(NewSmsMessage sms);

    Result<SmsMessage> update(SmsMessage sms);

    Optional<SmsMessage> findById(SmsId id);

    List<SmsMessage> findByLead(LeadId leadId);

    List<SmsMessage> findByRemoteNumber(PhoneNumber remoteNumber);

    List<SmsMessage> findRecent(int limit);
}
