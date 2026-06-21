package com.elitale.coldbirds.coldcalling.domain.event;

import com.elitale.coldbirds.coldcalling.domain.value.CallId;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import java.time.Instant;
import java.util.Objects;

/** All domain events emitted by the system. Handled by services and UI controllers. */
public sealed interface DomainEvent permits
        DomainEvent.IncomingCall,
        DomainEvent.CallAnswered,
        DomainEvent.CallEnded,
        DomainEvent.IncomingSms,
        DomainEvent.NumberReputationChanged {

    Instant occurredAt();

    record IncomingCall(
            CallId callId,
            PhoneNumber caller,
            PhoneNumber callee,
            Instant occurredAt
    ) implements DomainEvent {
        public IncomingCall {
            Objects.requireNonNull(callId,     "callId must not be null");
            Objects.requireNonNull(caller,     "caller must not be null");
            Objects.requireNonNull(callee,     "callee must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record CallAnswered(
            CallId callId,
            Instant occurredAt
    ) implements DomainEvent {
        public CallAnswered {
            Objects.requireNonNull(callId,     "callId must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record CallEnded(
            CallId callId,
            Instant occurredAt
    ) implements DomainEvent {
        public CallEnded {
            Objects.requireNonNull(callId,     "callId must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record IncomingSms(
            PhoneNumber from,
            PhoneNumber to,
            String body,
            Instant occurredAt
    ) implements DomainEvent {
        public IncomingSms {
            Objects.requireNonNull(from,       "from must not be null");
            Objects.requireNonNull(to,         "to must not be null");
            Objects.requireNonNull(body,       "body must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record NumberReputationChanged(
            PhoneNumberId phoneNumberId,
            NumberReputation newReputation,
            Instant occurredAt
    ) implements DomainEvent {
        public NumberReputationChanged {
            Objects.requireNonNull(phoneNumberId,  "phoneNumberId must not be null");
            Objects.requireNonNull(newReputation,  "newReputation must not be null");
            Objects.requireNonNull(occurredAt,     "occurredAt must not be null");
        }
    }
}
