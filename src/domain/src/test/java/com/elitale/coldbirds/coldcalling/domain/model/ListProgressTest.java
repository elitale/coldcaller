package com.elitale.coldbirds.coldcalling.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;

class ListProgressTest {

    private static CallListEntry entry(long id, CallListEntry.DialStatus status) {
        return new CallListEntry(id, new LeadId(id), (int) (id - 1), status);
    }

    private static CallList list(CallListEntry... entries) {
        return new CallList(new CallListId(1L), "Test", Optional.empty(),
                List.of(entries), Instant.now(), Instant.now());
    }

    @Test
    void empty_hasNoPendingAndNoResumeIndex() {
        ListProgress p = ListProgress.of(list());
        assertThat(p.total()).isZero();
        assertThat(p.dialed()).isZero();
        assertThat(p.pending()).isZero();
        assertThat(p.resumeIndex()).isEqualTo(-1);
        assertThat(p.isEmpty()).isTrue();
        assertThat(p.hasPending()).isFalse();
        assertThat(p.isComplete()).isFalse();
        assertThat(p.isResumable()).isFalse();
    }

    @Test
    void allPending_resumesFromZero_butIsNotResumable() {
        ListProgress p = ListProgress.of(list(
                entry(1, CallListEntry.DialStatus.PENDING),
                entry(2, CallListEntry.DialStatus.PENDING)));
        assertThat(p.total()).isEqualTo(2);
        assertThat(p.dialed()).isZero();
        assertThat(p.pending()).isEqualTo(2);
        assertThat(p.resumeIndex()).isZero();
        assertThat(p.hasPending()).isTrue();
        assertThat(p.isResumable()).isFalse();   // nothing dialed yet
        assertThat(p.isComplete()).isFalse();
        assertThat(p.isEmpty()).isFalse();
    }

    @Test
    void partial_resumesFromFirstPending() {
        ListProgress p = ListProgress.of(list(
                entry(1, CallListEntry.DialStatus.DIALED),
                entry(2, CallListEntry.DialStatus.PENDING)));
        assertThat(p.dialed()).isEqualTo(1);
        assertThat(p.pending()).isEqualTo(1);
        assertThat(p.resumeIndex()).isEqualTo(1);
        assertThat(p.isResumable()).isTrue();
    }

    @Test
    void complete_whenNonePending() {
        ListProgress p = ListProgress.of(list(
                entry(1, CallListEntry.DialStatus.DIALED),
                entry(2, CallListEntry.DialStatus.SKIPPED)));
        assertThat(p.pending()).isZero();
        assertThat(p.resumeIndex()).isEqualTo(-1);
        assertThat(p.isComplete()).isTrue();
        assertThat(p.hasPending()).isFalse();
        assertThat(p.isResumable()).isFalse();
    }

    @Test
    void interleaved_resumeIndexIsFirstPending_countsAreCorrect() {
        ListProgress p = ListProgress.of(list(
                entry(1, CallListEntry.DialStatus.DIALED),
                entry(2, CallListEntry.DialStatus.PENDING),
                entry(3, CallListEntry.DialStatus.DIALED),
                entry(4, CallListEntry.DialStatus.PENDING)));
        assertThat(p.total()).isEqualTo(4);
        assertThat(p.dialed()).isEqualTo(2);
        assertThat(p.pending()).isEqualTo(2);
        assertThat(p.resumeIndex()).isEqualTo(1);
        assertThat(p.isResumable()).isTrue();
    }

    @Test
    void allPending_buildsReadyPool() {
        ListProgress p = ListProgress.allPending(3);
        assertThat(p.total()).isEqualTo(3);
        assertThat(p.dialed()).isZero();
        assertThat(p.pending()).isEqualTo(3);
        assertThat(p.resumeIndex()).isZero();
        assertThat(p.isEmpty()).isFalse();
        assertThat(p.isResumable()).isFalse();
        assertThat(p.isComplete()).isFalse();
    }

    @Test
    void allPending_zero_isEmpty() {
        ListProgress p = ListProgress.allPending(0);
        assertThat(p.total()).isZero();
        assertThat(p.pending()).isZero();
        assertThat(p.resumeIndex()).isEqualTo(-1);
        assertThat(p.isEmpty()).isTrue();
    }

    @Test
    void allPending_negative_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ListProgress.allPending(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
