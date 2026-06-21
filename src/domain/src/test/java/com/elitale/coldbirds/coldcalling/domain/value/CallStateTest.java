package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class CallStateTest {

    @Test
    void idleHasNoPayload() {
        CallState state = new CallState.Idle();
        assertThat(state).isInstanceOf(CallState.Idle.class);
    }

    @Test
    void ringingHoldsCallerAndTime() {
        PhoneNumber caller = new PhoneNumber("+14155552671");
        Instant arrived = Instant.now();
        CallState state = new CallState.Ringing(caller, arrived);

        assertThat(((CallState.Ringing) state).caller()).isEqualTo(caller);
        assertThat(((CallState.Ringing) state).arrivedAt()).isEqualTo(arrived);
    }

    @Test
    void ringingRejectsNullCaller() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CallState.Ringing(null, Instant.now()));
    }

    @Test
    void endedHoldsReasonAndDuration() {
        EndReason reason = new EndReason.HungUp();
        Duration duration = Duration.ofSeconds(90);
        CallState state = new CallState.Ended(reason, duration);

        assertThat(((CallState.Ended) state).reason()).isEqualTo(reason);
        assertThat(((CallState.Ended) state).duration()).isEqualTo(duration);
    }

    @Test
    void switchIsExhaustive() {
        // Compiler enforces this — any missing case is a compile error.
        // This test proves the switch compiles and runs for every variant.
        CallState[] states = {
            new CallState.Idle(),
            new CallState.Ringing(new PhoneNumber("+14155552671"), Instant.now()),
            new CallState.Active(new PhoneNumber("+14155552671"), Instant.now()),
            new CallState.OnHold(new PhoneNumber("+14155552671"), Instant.now()),
            new CallState.Ended(new EndReason.HungUp(), Duration.ZERO)
        };

        for (CallState state : states) {
            String label = switch (state) {
                case CallState.Idle    ignored -> "idle";
                case CallState.Ringing ignored -> "ringing";
                case CallState.Active  ignored -> "active";
                case CallState.OnHold  ignored -> "on-hold";
                case CallState.Ended   ignored -> "ended";
            };
            assertThat(label).isNotBlank();
        }
    }
}
