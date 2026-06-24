package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.ui.support.ConnectivityHealth.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectivityHealthTest {

    private final Instant[] now = { Instant.parse("2024-01-01T00:00:00Z") };
    private final ConnectivityHealth health =
            new ConnectivityHealth(Duration.ofSeconds(8), Duration.ofSeconds(3), () -> now[0]);

    private void advance(long seconds) {
        now[0] = now[0].plusSeconds(seconds);
    }

    @Test
    void startsOptimisticallyOnline() {
        assertThat(health.current()).isEqualTo(State.ONLINE);
    }

    @Test
    void singleFailedProbe_isUnstable_notOffline() {
        health.onProbe(false);
        assertThat(health.current()).isEqualTo(State.UNSTABLE);
    }

    @Test
    void sustainedLoss_escalatesToOffline_afterGrace() {
        health.onProbe(false);
        advance(5);
        assertThat(health.current()).isEqualTo(State.UNSTABLE);
        advance(3);                       // total 8s == offlineGrace
        assertThat(health.current()).isEqualTo(State.OFFLINE);
    }

    @Test
    void recovery_needsSustainedReachability_beforeOnline() {
        health.onProbe(false);
        advance(8);
        assertThat(health.current()).isEqualTo(State.OFFLINE);

        health.onProbe(true);             // first good probe — not online yet
        assertThat(health.current()).isEqualTo(State.OFFLINE);
        advance(3);                       // sustained == stability
        assertThat(health.current()).isEqualTo(State.ONLINE);
    }

    @Test
    void flap_duringRecovery_resetsStabilityTimer() {
        health.onProbe(false);
        assertThat(health.current()).isEqualTo(State.UNSTABLE);
        health.onProbe(true);             // start recovery
        advance(2);
        health.onProbe(false);            // blip interrupts recovery
        advance(2);
        assertThat(health.current()).isEqualTo(State.UNSTABLE);   // did NOT flip to ONLINE
    }
}
