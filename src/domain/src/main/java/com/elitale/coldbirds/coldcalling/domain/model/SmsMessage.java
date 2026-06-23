package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** An inbound or outbound SMS message. */
public record SmsMessage(
        SmsId id,
        CallDirection direction,
        PhoneNumberId phoneNumberId,
        Optional<LeadId> leadId,
        PhoneNumber remoteNumber,
        String body,
        SmsStatus status,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {
    public SmsMessage {
        Objects.requireNonNull(id,            "id must not be null");
        Objects.requireNonNull(direction,     "direction must not be null");
        Objects.requireNonNull(phoneNumberId, "phoneNumberId must not be null");
        Objects.requireNonNull(leadId,        "leadId must not be null");
        Objects.requireNonNull(remoteNumber,  "remoteNumber must not be null");
        Objects.requireNonNull(body,          "body must not be null");
        Objects.requireNonNull(status,        "status must not be null");
        Objects.requireNonNull(sentAt,        "sentAt must not be null");
        Objects.requireNonNull(createdAt,     "createdAt must not be null");
        Objects.requireNonNull(updatedAt,     "updatedAt must not be null");
    }
}
