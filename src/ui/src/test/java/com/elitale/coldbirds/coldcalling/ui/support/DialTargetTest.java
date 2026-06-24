package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.CallListEntry;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;

class DialTargetTest {

    private static CallList list(CallListEntry.DialStatus... statuses) {
        List<CallListEntry> entries = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            entries.add(new CallListEntry(i + 1, new LeadId(i + 1), i, statuses[i]));
        }
        return new CallList(new CallListId(7L), "Q1 Leads", Optional.empty(),
                entries, Instant.now(), Instant.now());
    }

    @Test
    void allLeads_readyWhenLeadsExist() {
        DialTarget t = new DialTarget.AllLeads(5);
        assertThat(t.title()).isEqualTo("All Leads");
        assertThat(t.readiness().kind()).isEqualTo(PowerDialerReadiness.Kind.READY);
        assertThat(t.selectorLabel()).isEqualTo("All Leads  —  5 leads");
    }

    @Test
    void allLeads_emptyWhenNoLeads() {
        DialTarget t = new DialTarget.AllLeads(0);
        assertThat(t.readiness().kind()).isEqualTo(PowerDialerReadiness.Kind.EMPTY);
        assertThat(t.selectorLabel()).isEqualTo("All Leads  —  no leads");
    }

    @Test
    void allLeads_rejectsNegativeCount() {
        assertThatThrownBy(() -> new DialTarget.AllLeads(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneList_usesListNameAndResumeProgress() {
        DialTarget t = new DialTarget.OneList(list(
                CallListEntry.DialStatus.DIALED, CallListEntry.DialStatus.PENDING));
        assertThat(t.title()).isEqualTo("Q1 Leads");
        assertThat(t.readiness().kind()).isEqualTo(PowerDialerReadiness.Kind.RESUMABLE);
        assertThat(t.selectorLabel()).isEqualTo("Q1 Leads  —  1 of 2 left");
    }

    @Test
    void oneList_completeWhenAllDialed() {
        DialTarget t = new DialTarget.OneList(list(
                CallListEntry.DialStatus.DIALED, CallListEntry.DialStatus.DIALED));
        assertThat(t.readiness().kind()).isEqualTo(PowerDialerReadiness.Kind.COMPLETE);
        assertThat(t.selectorLabel()).isEqualTo("Q1 Leads  —  all dialed");
    }

    @Test
    void oneList_emptyWhenNoEntries() {
        DialTarget t = new DialTarget.OneList(list());
        assertThat(t.readiness().kind()).isEqualTo(PowerDialerReadiness.Kind.EMPTY);
        assertThat(t.selectorLabel()).isEqualTo("Q1 Leads  —  no leads");
    }
}
