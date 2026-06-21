package com.elitale.coldbirds.coldcalling.domain.value;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Live state of a single call leg. Exhaustively pattern-matched at all call sites. */
public sealed interface CallState permits
        CallState.Idle,
        CallState.Ringing,
        CallState.Active,
        CallState.OnHold,
        CallState.Ended {

    /** No active or pending call. */
    record Idle() implements CallState {}

    /** Inbound call arriving — not yet answered. */
    record Ringing(PhoneNumber caller, Instant arrivedAt) implements CallState {
        public Ringing {
            Objects.requireNonNull(caller,    "caller must not be null");
            Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
        }
    }

    /** Call is connected — RTP flowing. */
    record Active(PhoneNumber remote, Instant connectedAt) implements CallState {
        public Active {
            Objects.requireNonNull(remote,      "remote must not be null");
            Objects.requireNonNull(connectedAt, "connectedAt must not be null");
        }
    }

    /** Call is on hold — RTP paused. */
    record OnHold(PhoneNumber remote, Instant heldAt) implements CallState {
        public OnHold {
            Objects.requireNonNull(remote, "remote must not be null");
            Objects.requireNonNull(heldAt, "heldAt must not be null");
        }
    }

    /** Call has terminated. */
    record Ended(EndReason reason, Duration duration) implements CallState {
        public Ended {
            Objects.requireNonNull(reason,   "reason must not be null");
            Objects.requireNonNull(duration, "duration must not be null");
        }
    }
}
