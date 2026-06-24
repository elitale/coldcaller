package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeadSelectionModelTest {

    @Test
    void toggleSelectsThenDeselects() {
        LeadSelectionModel m = new LeadSelectionModel();
        assertThat(m.toggle(new LeadId(1L))).isTrue();
        assertThat(m.isSelected(new LeadId(1L))).isTrue();
        assertThat(m.toggle(new LeadId(1L))).isFalse();
        assertThat(m.isSelected(new LeadId(1L))).isFalse();
    }

    @Test
    void selectedIdsPreserveInsertionOrderAndAreUnmodifiable() {
        LeadSelectionModel m = new LeadSelectionModel();
        m.select(new LeadId(3L));
        m.select(new LeadId(1L));
        m.select(new LeadId(2L));
        assertThat(m.selectedIds())
                .extracting(LeadId::value)
                .containsExactly(3L, 1L, 2L);
    }

    @Test
    void selectAllAndClear() {
        LeadSelectionModel m = new LeadSelectionModel();
        m.selectAll(List.of(new LeadId(1L), new LeadId(2L)));
        assertThat(m.count()).isEqualTo(2);
        assertThat(m.isEmpty()).isFalse();
        m.clear();
        assertThat(m.isEmpty()).isTrue();
    }
}
