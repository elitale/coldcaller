package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.ui.support.CallReadiness.Readiness;
import org.junit.jupiter.api.Test;

import static com.elitale.coldbirds.coldcalling.ui.support.ConnectivityHealth.State.OFFLINE;
import static com.elitale.coldbirds.coldcalling.ui.support.ConnectivityHealth.State.ONLINE;
import static com.elitale.coldbirds.coldcalling.ui.support.ConnectivityHealth.State.UNSTABLE;
import static org.assertj.core.api.Assertions.assertThat;

class CallReadinessTest {

    @Test
    void onlineAndRegistered_isReady() {
        assertThat(CallReadiness.resolve(ONLINE, RegistrationHealth.State.REGISTERED))
                .isEqualTo(Readiness.READY);
        assertThat(CallReadiness.callable(ONLINE, RegistrationHealth.State.REGISTERED)).isTrue();
    }

    @Test
    void onlineButReconnectingSip_isReconnecting() {
        assertThat(CallReadiness.resolve(ONLINE, RegistrationHealth.State.RECONNECTING))
                .isEqualTo(Readiness.RECONNECTING);
    }

    @Test
    void onlineButNotRegistered_isOffline() {
        assertThat(CallReadiness.resolve(ONLINE, RegistrationHealth.State.OFFLINE))
                .isEqualTo(Readiness.OFFLINE);
    }

    @Test
    void networkUnstable_isReconnecting_regardlessOfSip() {
        assertThat(CallReadiness.resolve(UNSTABLE, RegistrationHealth.State.REGISTERED))
                .isEqualTo(Readiness.RECONNECTING);
        assertThat(CallReadiness.resolve(UNSTABLE, RegistrationHealth.State.OFFLINE))
                .isEqualTo(Readiness.RECONNECTING);
    }

    @Test
    void internetDown_absorbsSip_isOffline() {
        assertThat(CallReadiness.resolve(OFFLINE, RegistrationHealth.State.REGISTERED))
                .isEqualTo(Readiness.OFFLINE);
        assertThat(CallReadiness.resolve(OFFLINE, RegistrationHealth.State.RECONNECTING))
                .isEqualTo(Readiness.OFFLINE);
        assertThat(CallReadiness.callable(OFFLINE, RegistrationHealth.State.REGISTERED)).isFalse();
    }
}
