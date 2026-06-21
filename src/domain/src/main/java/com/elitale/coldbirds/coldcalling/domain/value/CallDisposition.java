package com.elitale.coldbirds.coldcalling.domain.value;

import java.time.Instant;
import java.util.Objects;

/** Outcome recorded after a call ends. */
public sealed interface CallDisposition permits
        CallDisposition.Interested,
        CallDisposition.NotInterested,
        CallDisposition.Callback,
        CallDisposition.Voicemail,
        CallDisposition.NoAnswer,
        CallDisposition.Busy,
        CallDisposition.DNC,
        CallDisposition.Failed {

    record Interested()    implements CallDisposition {}
    record NotInterested() implements CallDisposition {}
    record Voicemail()     implements CallDisposition {}
    record NoAnswer()      implements CallDisposition {}
    record Busy()          implements CallDisposition {}
    record DNC()           implements CallDisposition {}

    record Callback(Instant scheduledAt) implements CallDisposition {
        public Callback {
            Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");
        }
    }

    record Failed(String reason) implements CallDisposition {
        public Failed {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
