package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class CallOutcomeTest {

    private static Optional<CallDisposition> of(CallDisposition d) {
        return Optional.of(d);
    }

    @Test
    void talkedDispositionsAreConnected() {
        assertThat(CallOutcome.classify(of(new CallDisposition.Interested()), false)).isEqualTo(CallOutcome.CONNECTED);
        assertThat(CallOutcome.classify(of(new CallDisposition.NotInterested()), false)).isEqualTo(CallOutcome.CONNECTED);
        assertThat(CallOutcome.classify(of(new CallDisposition.Callback(Instant.now())), false)).isEqualTo(CallOutcome.CONNECTED);
    }

    @Test
    void unreachedDispositionsMapToOutcome() {
        assertThat(CallOutcome.classify(of(new CallDisposition.NoAnswer()), true)).isEqualTo(CallOutcome.NO_ANSWER);
        assertThat(CallOutcome.classify(of(new CallDisposition.Busy()), true)).isEqualTo(CallOutcome.NO_ANSWER);
        assertThat(CallOutcome.classify(of(new CallDisposition.Voicemail()), true)).isEqualTo(CallOutcome.VOICEMAIL);
        assertThat(CallOutcome.classify(of(new CallDisposition.Failed("x")), true)).isEqualTo(CallOutcome.FAILED);
        assertThat(CallOutcome.classify(of(new CallDisposition.DNC()), true)).isEqualTo(CallOutcome.DNC);
    }

    @Test
    void noDispositionFallsBackToConnectedFlag() {
        assertThat(CallOutcome.classify(Optional.empty(), true)).isEqualTo(CallOutcome.CONNECTED);
        assertThat(CallOutcome.classify(Optional.empty(), false)).isEqualTo(CallOutcome.NO_ANSWER);
    }

    @Test
    void styleClassIsKebabCased() {
        assertThat(CallOutcome.NO_ANSWER.styleClass()).isEqualTo("outcome-no-answer");
        assertThat(CallOutcome.CONNECTED.styleClass()).isEqualTo("outcome-connected");
    }
}
