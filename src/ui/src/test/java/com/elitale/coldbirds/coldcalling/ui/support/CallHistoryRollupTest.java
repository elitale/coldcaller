package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.CallId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.ui.support.CallHistoryRollup.Summary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class CallHistoryRollupTest {

    private static final PhoneNumber A = new PhoneNumber("+14155550001");
    private static final PhoneNumber B = new PhoneNumber("+14155550002");
    private static final Instant T0 = Instant.parse("2026-06-24T10:00:00Z");

    private static Call call(PhoneNumber remote, Instant startedAt,
                             Optional<CallDisposition> disp, boolean answered) {
        return new Call(new CallId(1L), CallDirection.OUTBOUND, new PhoneNumberId(1L),
                Optional.empty(), remote, disp, startedAt,
                answered ? Optional.of(startedAt) : Optional.empty(), Optional.empty(),
                answered ? Optional.of(1000L) : Optional.empty(),
                Optional.empty(), Optional.empty(), startedAt, startedAt);
    }

    @Test
    void rollsUpOneRowPerNumberNewestFirst() {
        List<Summary> rows = CallHistoryRollup.rollup(List.of(
                call(A, T0.plusSeconds(30), Optional.empty(), false),
                call(B, T0.plusSeconds(20), Optional.empty(), false),
                call(A, T0.plusSeconds(10), Optional.empty(), false)));

        assertThat(rows).extracting(Summary::number).containsExactly(A.value(), B.value());
        assertThat(rows.get(0).callCount()).isEqualTo(2);
    }

    @Test
    void badgeShowsMostValuableNotLatest() {
        // Newest is a no-answer; an earlier call was Interested.
        Summary s = CallHistoryRollup.summarize(List.of(
                call(A, T0.plusSeconds(20), Optional.of(new CallDisposition.NoAnswer()), false),
                call(A, T0.plusSeconds(10), Optional.of(new CallDisposition.Interested()), true)));

        assertThat(s.badgeDisposition()).get().isInstanceOf(CallDisposition.Interested.class);
        assertThat(s.lastOutcome()).isEqualTo(CallOutcome.NO_ANSWER); // last attempt
    }

    @Test
    void containsDncSurfacesAnyDnc() {
        Summary s = CallHistoryRollup.summarize(List.of(
                call(A, T0.plusSeconds(20), Optional.of(new CallDisposition.NoAnswer()), false),
                call(A, T0.plusSeconds(10), Optional.of(new CallDisposition.DNC()), true)));
        assertThat(s.containsDnc()).isTrue();
    }

    @Test
    void callbackDueAtIsLatestPromise() {
        Instant early = T0.plusSeconds(100);
        Instant late = T0.plusSeconds(500);
        Summary s = CallHistoryRollup.summarize(List.of(
                call(A, T0.plusSeconds(20), Optional.of(new CallDisposition.Callback(late)), true),
                call(A, T0.plusSeconds(10), Optional.of(new CallDisposition.Callback(early)), true)));
        assertThat(s.callbackDueAt()).contains(late);
    }

    @Test
    void noCallbackMeansEmptyDueAt() {
        Summary s = CallHistoryRollup.summarize(List.of(
                call(A, T0, Optional.of(new CallDisposition.Interested()), true)));
        assertThat(s.callbackDueAt()).isEmpty();
    }
}
