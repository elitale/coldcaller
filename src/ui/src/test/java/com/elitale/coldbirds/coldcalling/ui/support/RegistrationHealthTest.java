package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

final class RegistrationHealthTest {

    private Instant now = Instant.parse("2026-06-24T10:00:00Z");

    private RegistrationHealth health() {
        return new RegistrationHealth(Duration.ofSeconds(90), () -> now);
    }

    private void advanceSeconds(long s) {
        now = now.plusSeconds(s);
    }

    @Test
    void startsOffline() {
        assertThat(health().current()).isEqualTo(RegistrationHealth.State.OFFLINE);
    }

    @Test
    void registeredGoesGreen() {
        RegistrationHealth h = health();
        h.onRegistrationChanged(true);
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.REGISTERED);
    }

    @Test
    void droppedAfterRegisteredGoesAmber() {
        RegistrationHealth h = health();
        h.onRegistered();
        h.onUnregistered();
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.RECONNECTING);
    }

    @Test
    void amberStaysWithinGrace() {
        RegistrationHealth h = health();
        h.onRegistered();
        h.onUnregistered();
        advanceSeconds(89);
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.RECONNECTING);
    }

    @Test
    void amberEscalatesToOfflineAfterGrace() {
        RegistrationHealth h = health();
        h.onRegistered();
        h.onUnregistered();
        advanceSeconds(90);
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.OFFLINE);
    }

    @Test
    void reconnectWithinGraceRecoversGreen() {
        RegistrationHealth h = health();
        h.onRegistered();
        h.onUnregistered();
        advanceSeconds(30);
        h.onRegistered();
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.REGISTERED);
    }

    @Test
    void neverRegisteredThenDroppedStaysOfflineNotAmber() {
        RegistrationHealth h = health();
        h.onUnregistered();
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.OFFLINE);
    }

    @Test
    void credentialsAbsentForcesOffline() {
        RegistrationHealth h = health();
        h.onRegistered();
        h.onCredentialsAbsent();
        assertThat(h.current()).isEqualTo(RegistrationHealth.State.OFFLINE);
    }

    @Test
    void healthyRepeatedRefreshNeverShowsAmber() {
        RegistrationHealth h = health();
        for (int cycle = 0; cycle < 3; cycle++) {
            h.onRegistered();        // a successful (re-)REGISTER fires only true
            advanceSeconds(60);
            assertThat(h.current()).isEqualTo(RegistrationHealth.State.REGISTERED);
        }
    }
}
