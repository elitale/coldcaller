package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class LeadFilterTest {

    @Test
    void allHasNoConstraintsAndDefaultLimit() {
        LeadFilter filter = LeadFilter.all();
        assertThat(filter.search()).isEmpty();
        assertThat(filter.columnFilters()).isEmpty();
        assertThat(filter.statuses()).isEmpty();
        assertThat(filter.tags()).isEmpty();
        assertThat(filter.dnc()).isEqualTo(LeadFilter.DncFilter.ANY);
        assertThat(filter.listId()).isEmpty();
        assertThat(filter.customFields()).isEmpty();
        assertThat(filter.cursor()).isEmpty();
        assertThat(filter.limit()).isEqualTo(LeadFilter.DEFAULT_LIMIT);
    }

    @Test
    void limitIsClamped() {
        assertThat(LeadFilter.builder().limit(0).build().limit()).isEqualTo(1);
        assertThat(LeadFilter.builder().limit(-10).build().limit()).isEqualTo(1);
        assertThat(LeadFilter.builder().limit(5000).build().limit()).isEqualTo(LeadFilter.MAX_LIMIT);
        assertThat(LeadFilter.builder().limit(75).build().limit()).isEqualTo(75);
    }

    @Test
    void builderBlankSearchBecomesEmpty() {
        assertThat(LeadFilter.builder().search("  ").build().search()).isEmpty();
        assertThat(LeadFilter.builder().search(" acme ").build().search()).contains("acme");
    }

    @Test
    void builderSkipsBlankColumnAndCustomFilters() {
        LeadFilter filter = LeadFilter.builder()
                .column(LeadColumn.COMPANY, "  ")
                .column(LeadColumn.TITLE, "VP")
                .customField("Timezone", "")
                .customField("Source", "Apollo")
                .build();
        assertThat(filter.columnFilters()).containsExactly(entry(LeadColumn.TITLE, "VP"));
        assertThat(filter.customFields()).containsExactly(entry("Source", "Apollo"));
    }

    @Test
    void collectionsAreUnmodifiable() {
        LeadFilter filter = LeadFilter.builder().statuses(Set.of(LeadStatus.NEW)).build();
        assertThatThrownBy(() -> filter.statuses().add(LeadStatus.DNC))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withCursorAdvancesPage() {
        Cursor cursor = new Cursor(123L, 9L);
        LeadFilter filter = LeadFilter.builder().search("x").build().withCursor(cursor);
        assertThat(filter.cursor()).contains(cursor);
        assertThat(filter.search()).contains("x");
    }
}
