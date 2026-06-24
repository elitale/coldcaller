package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.Cursor;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Page;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LeadsPagerTest {

    @Test
    void firstRequestUsesBaseFilterWithoutCursor() {
        final LeadsPager pager = new LeadsPager();
        final LeadFilter base = LeadFilter.builder().search("acme").build();
        pager.reset(base);

        assertThat(pager.nextRequest()).isEqualTo(base);
        assertThat(pager.nextRequest().cursor()).isEmpty();
        assertThat(pager.isFirstPageLoaded()).isFalse();
        assertThat(pager.hasMore()).isFalse();
    }

    @Test
    void acceptingPageWithCursorAdvancesRequestAndAccumulatesRows() {
        final LeadsPager pager = new LeadsPager();
        final LeadFilter base = LeadFilter.builder().limit(2).build();
        pager.reset(base);

        final Cursor cursor = new Cursor(1000L, 4L);
        pager.accept(new Page<>(List.of(lead(5), lead(4)), Optional.of(cursor), 5));

        assertThat(pager.isFirstPageLoaded()).isTrue();
        assertThat(pager.hasMore()).isTrue();
        assertThat(pager.loadedCount()).isEqualTo(2);
        assertThat(pager.total()).isEqualTo(5);
        assertThat(pager.lastPageRows()).extracting(l -> l.id().value()).containsExactly(5L, 4L);
        assertThat(pager.nextRequest()).isEqualTo(base.withCursor(cursor));
        assertThat(pager.nextRequest().cursor()).contains(cursor);
    }

    @Test
    void acceptingFinalPageStopsPagingAndKeepsAllRows() {
        final LeadsPager pager = new LeadsPager();
        pager.reset(LeadFilter.builder().limit(2).build());

        pager.accept(new Page<>(List.of(lead(5), lead(4)), Optional.of(new Cursor(1000L, 4L)), 3));
        pager.accept(new Page<>(List.of(lead(3)), Optional.empty(), 3));

        assertThat(pager.hasMore()).isFalse();
        assertThat(pager.loadedCount()).isEqualTo(3);
        assertThat(pager.lastPageRows()).extracting(l -> l.id().value()).containsExactly(3L);
        assertThat(pager.rows()).extracting(l -> l.id().value()).containsExactly(5L, 4L, 3L);
    }

    @Test
    void resetClearsRowsCursorAndTotal() {
        final LeadsPager pager = new LeadsPager();
        pager.reset(LeadFilter.all());
        pager.accept(new Page<>(List.of(lead(1)), Optional.of(new Cursor(1L, 1L)), 9));

        pager.reset(LeadFilter.builder().search("x").build());

        assertThat(pager.loadedCount()).isZero();
        assertThat(pager.total()).isZero();
        assertThat(pager.hasMore()).isFalse();
        assertThat(pager.isFirstPageLoaded()).isFalse();
        assertThat(pager.lastPageRows()).isEmpty();
    }

    private static Lead lead(long id) {
        return new Lead(
                new LeadId(id), Optional.empty(), Optional.empty(),
                new PhoneNumber(String.format("+1415555%04d", id)),
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), false, Map.of(), LeadStatus.NEW,
                Instant.ofEpochMilli(id), Instant.ofEpochMilli(id));
    }
}
