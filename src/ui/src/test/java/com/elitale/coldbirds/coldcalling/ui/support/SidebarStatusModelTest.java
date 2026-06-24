package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.ui.support.SidebarStatusModel.ReturnKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class SidebarStatusModelTest {

    @Test
    void defaultsAreQuiet() {
        SidebarStatusModel m = new SidebarStatusModel();
        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.NONE);
        assertThat(m.registration()).isEqualTo(RegistrationHealth.State.OFFLINE);
        assertThat(m.messagesActivity()).isFalse();
    }

    @Test
    void inboundRingWinsOverEverything() {
        SidebarStatusModel m = new SidebarStatusModel();
        m.setLiveCall(true);
        m.setPowerDialer(Optional.of("Dialing · #3"));
        m.setInboundRing(Optional.of("+1 512…"));

        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.INBOUND_RING);
        assertThat(m.returnRow().label()).isEqualTo("+1 512…");
    }

    @Test
    void liveCallWinsOverPowerDialer() {
        SidebarStatusModel m = new SidebarStatusModel();
        m.setPowerDialer(Optional.of("Dialing · #3"));
        m.setLiveCall(true);

        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.LIVE_CALL);
    }

    @Test
    void powerDialerShowsWhenNothingElse() {
        SidebarStatusModel m = new SidebarStatusModel();
        m.setPowerDialer(Optional.of("Dialing · #3"));

        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.POWER_DIALER);
        assertThat(m.returnRow().label()).isEqualTo("Dialing · #3");
    }

    @Test
    void clearingHigherPriorityFallsThrough() {
        SidebarStatusModel m = new SidebarStatusModel();
        m.setInboundRing(Optional.of("+1 512…"));
        m.setLiveCall(true);
        m.setPowerDialer(Optional.of("Dialing · #3"));

        m.setInboundRing(Optional.empty());
        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.LIVE_CALL);
        m.setLiveCall(false);
        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.POWER_DIALER);
        m.setPowerDialer(Optional.empty());
        assertThat(m.returnRow().kind()).isEqualTo(ReturnKind.NONE);
    }

    @Test
    void messagesActivityAndRegistrationPassThrough() {
        SidebarStatusModel m = new SidebarStatusModel();
        m.setMessagesActivity(true);
        m.setRegistration(RegistrationHealth.State.REGISTERED);

        assertThat(m.messagesActivity()).isTrue();
        assertThat(m.registration()).isEqualTo(RegistrationHealth.State.REGISTERED);
    }

    @Test
    void listenerFiresOnChangeButNotOnNoOp() {
        SidebarStatusModel m = new SidebarStatusModel();
        AtomicInteger fires = new AtomicInteger();
        m.addListener(fires::incrementAndGet);

        m.setLiveCall(true);
        m.setLiveCall(true); // no-op
        m.setMessagesActivity(true);
        m.setMessagesActivity(true); // no-op

        assertThat(fires.get()).isEqualTo(2);
    }
}
