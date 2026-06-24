package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.ui.support.NavSelectionModel.Destination;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class NavSelectionModelTest {

    @Test
    void defaultsToDialer() {
        NavSelectionModel model = new NavSelectionModel();
        assertThat(model.active()).isEqualTo(Destination.DIALER);
        assertThat(model.isActive(Destination.DIALER)).isTrue();
    }

    @Test
    void selectChangesActiveAndNotifiesOnce() {
        NavSelectionModel model = new NavSelectionModel();
        List<Destination> notified = new ArrayList<>();
        model.addListener(notified::add);

        model.select(Destination.LEADS);

        assertThat(model.active()).isEqualTo(Destination.LEADS);
        assertThat(model.isActive(Destination.LEADS)).isTrue();
        assertThat(model.isActive(Destination.DIALER)).isFalse();
        assertThat(notified).containsExactly(Destination.LEADS);
    }

    @Test
    void selectingActiveDestinationDoesNotNotify() {
        NavSelectionModel model = new NavSelectionModel(Destination.MESSAGES);
        List<Destination> notified = new ArrayList<>();
        model.addListener(notified::add);

        model.select(Destination.MESSAGES);

        assertThat(notified).isEmpty();
    }

    @Test
    void allListenersAreNotified() {
        NavSelectionModel model = new NavSelectionModel();
        List<Destination> a = new ArrayList<>();
        List<Destination> b = new ArrayList<>();
        model.addListener(a::add);
        model.addListener(b::add);

        model.select(Destination.SETTINGS);

        assertThat(a).containsExactly(Destination.SETTINGS);
        assertThat(b).containsExactly(Destination.SETTINGS);
    }
}
