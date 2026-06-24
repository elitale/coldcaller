package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeadFilterStateTest {

    @Test
    void emptyStateBuildsTheUnconstrainedFilter() {
        final LeadFilterState state = new LeadFilterState();
        assertThat(state.hasActiveFacets()).isFalse();
        assertThat(state.toFilter()).isEqualTo(LeadFilter.all());
    }

    @Test
    void searchIsAppliedButIsNotAFacet() {
        final LeadFilterState state = new LeadFilterState();
        state.setSearch("  acme  ");
        assertThat(state.search()).isEqualTo("acme");
        assertThat(state.toFilter().search()).contains("acme");
        assertThat(state.hasActiveFacets()).isFalse();
    }

    @Test
    void statusFacetFlowsIntoTheFilter() {
        final LeadFilterState state = new LeadFilterState();
        state.statuses().add(LeadStatus.INTERESTED);
        state.statuses().add(LeadStatus.CALLBACK);
        assertThat(state.hasActiveFacets()).isTrue();
        assertThat(state.toFilter().statuses())
                .containsExactlyInAnyOrder(LeadStatus.INTERESTED, LeadStatus.CALLBACK);
    }

    @Test
    void tagFacetFlowsIntoTheFilter() {
        final LeadFilterState state = new LeadFilterState();
        state.tags().add("vip");
        assertThat(state.hasActiveFacets()).isTrue();
        assertThat(state.toFilter().tags()).containsExactly("vip");
    }

    @Test
    void dncTriStateFlowsIntoTheFilter() {
        final LeadFilterState state = new LeadFilterState();
        state.setDnc(LeadFilter.DncFilter.ONLY);
        assertThat(state.hasActiveFacets()).isTrue();
        assertThat(state.toFilter().dnc()).isEqualTo(LeadFilter.DncFilter.ONLY);
    }

    @Test
    void customFieldFacetFlowsIntoTheFilter() {
        final LeadFilterState state = new LeadFilterState();
        state.setCustomField("Source", "apollo");
        assertThat(state.hasActiveFacets()).isTrue();
        assertThat(state.toFilter().customFields()).containsEntry("Source", "apollo");
    }

    @Test
    void blankCustomFieldKeyIsNotActive() {
        final LeadFilterState state = new LeadFilterState();
        state.setCustomField("   ", "apollo");
        assertThat(state.hasActiveFacets()).isFalse();
        assertThat(state.toFilter().customFields()).isEmpty();
    }

    @Test
    void clearFacetsResetsFacetsButKeepsSearch() {
        final LeadFilterState state = new LeadFilterState();
        state.setSearch("acme");
        state.statuses().add(LeadStatus.NEW);
        state.tags().add("vip");
        state.setDnc(LeadFilter.DncFilter.EXCLUDE);
        state.setCustomField("Source", "apollo");

        state.clearFacets();

        assertThat(state.hasActiveFacets()).isFalse();
        assertThat(state.search()).isEqualTo("acme");
        assertThat(state.toFilter().search()).contains("acme");
        assertThat(state.toFilter().statuses()).isEmpty();
        assertThat(state.toFilter().tags()).isEmpty();
        assertThat(state.toFilter().dnc()).isEqualTo(LeadFilter.DncFilter.ANY);
        assertThat(state.toFilter().customFields()).isEmpty();
    }

    @Test
    void limitIsHonoured() {
        final LeadFilterState state = new LeadFilterState();
        state.setLimit(25);
        assertThat(state.toFilter().limit()).isEqualTo(25);
    }
}
