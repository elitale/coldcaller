package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.PowerDialerState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Tracks the runtime state of a power dialer run. */
public record PowerDialerSession(
        long id,
        CallListId callListId,
        int currentPosition,
        PowerDialerState state,
        int dialedCount,
        int connectedCount,
        Instant startedAt,
        Optional<Instant> endedAt
) {
    public PowerDialerSession {
        Objects.requireNonNull(callListId, "callListId must not be null");
        Objects.requireNonNull(state,      "state must not be null");
        Objects.requireNonNull(startedAt,  "startedAt must not be null");
        Objects.requireNonNull(endedAt,    "endedAt must not be null");
        if (currentPosition < 0)  throw new IllegalArgumentException("currentPosition must be >= 0");
        if (dialedCount < 0)      throw new IllegalArgumentException("dialedCount must be >= 0");
        if (connectedCount < 0)   throw new IllegalArgumentException("connectedCount must be >= 0");
        if (connectedCount > dialedCount)
            throw new IllegalArgumentException("connectedCount cannot exceed dialedCount");
    }
}
