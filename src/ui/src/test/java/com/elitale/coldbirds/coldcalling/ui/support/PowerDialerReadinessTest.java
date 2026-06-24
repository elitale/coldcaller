package com.elitale.coldbirds.coldcalling.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.elitale.coldbirds.coldcalling.domain.model.ListProgress;

class PowerDialerReadinessTest {

    @Test
    void empty_disablesStartAndOffersBuildList() {
        PowerDialerReadiness r = PowerDialerReadiness.of(new ListProgress(0, 0, 0, -1));
        assertThat(r.kind()).isEqualTo(PowerDialerReadiness.Kind.EMPTY);
        assertThat(r.primaryEnabled()).isFalse();
        assertThat(r.showBuildList()).isTrue();
        assertThat(r.primaryLabel()).isEqualTo("Start dialing");
        assertThat(r.statusLine()).contains("add leads");
    }

    @Test
    void ready_whenAllPending_enablesStart() {
        PowerDialerReadiness r = PowerDialerReadiness.of(new ListProgress(3, 0, 3, 0));
        assertThat(r.kind()).isEqualTo(PowerDialerReadiness.Kind.READY);
        assertThat(r.primaryEnabled()).isTrue();
        assertThat(r.showBuildList()).isFalse();
        assertThat(r.primaryLabel()).isEqualTo("Start dialing");
        assertThat(r.statusLine()).contains("3 leads");
    }

    @Test
    void resumable_whenPartlyDialed_labelsResumePosition() {
        PowerDialerReadiness r = PowerDialerReadiness.of(new ListProgress(5, 2, 3, 2));
        assertThat(r.kind()).isEqualTo(PowerDialerReadiness.Kind.RESUMABLE);
        assertThat(r.primaryEnabled()).isTrue();
        assertThat(r.showBuildList()).isFalse();
        assertThat(r.primaryLabel()).isEqualTo("Resume from #3"); // resumeIndex 2 → 1-based #3
        assertThat(r.statusLine()).contains("2 done").contains("3 left");
    }

    @Test
    void complete_whenNonePending_disablesStart() {
        PowerDialerReadiness r = PowerDialerReadiness.of(new ListProgress(3, 3, 0, -1));
        assertThat(r.kind()).isEqualTo(PowerDialerReadiness.Kind.COMPLETE);
        assertThat(r.primaryEnabled()).isFalse();
        assertThat(r.showBuildList()).isFalse();
        assertThat(r.primaryLabel()).isEqualTo("List complete");
        assertThat(r.statusLine()).isEqualTo("All 3 leads dialed");
    }

    @Test
    void singleLeadList_usesSingularNoun() {
        PowerDialerReadiness r = PowerDialerReadiness.of(new ListProgress(1, 0, 1, 0));
        assertThat(r.statusLine()).contains("1 lead").doesNotContain("1 leads");
    }
}
