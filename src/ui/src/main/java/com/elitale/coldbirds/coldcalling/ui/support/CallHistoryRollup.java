package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Collapses the per-call log into one summary per remote number (the per-lead rollup default).
 *
 * <p>The badge shows the most <em>valuable</em> disposition in the stack (Interested &gt; Callback
 * &gt; …) so a prospect who was "Interested" yesterday and "No answer" today still reads
 * Interested — while the {@link CallOutcome} reflects the <em>last attempt</em>. {@code containsDnc}
 * surfaces a compliance event the rollup must never hide. Pure and unit-tested.
 */
public final class CallHistoryRollup {

    /** One rolled-up prospect row's data (lead/country/dialed-from resolved separately). */
    public record Summary(
            String number,
            Instant lastCallAt,
            int callCount,
            Optional<CallDisposition> badgeDisposition,
            CallOutcome lastOutcome,
            Optional<Instant> callbackDueAt,
            boolean containsDnc) {
        public Summary {
            Objects.requireNonNull(number, "number must not be null");
            Objects.requireNonNull(lastCallAt, "lastCallAt must not be null");
            Objects.requireNonNull(badgeDisposition, "badgeDisposition must not be null");
            Objects.requireNonNull(lastOutcome, "lastOutcome must not be null");
            Objects.requireNonNull(callbackDueAt, "callbackDueAt must not be null");
            if (callCount <= 0) {
                throw new IllegalArgumentException("callCount must be positive");
            }
        }
    }

    private CallHistoryRollup() {
    }

    /** Roll up calls (newest first) into one summary per number, preserving newest-first order. */
    public static List<Summary> rollup(List<Call> callsNewestFirst) {
        Objects.requireNonNull(callsNewestFirst, "callsNewestFirst must not be null");
        final Map<String, List<Call>> byNumber = new LinkedHashMap<>();
        for (Call call : callsNewestFirst) {
            byNumber.computeIfAbsent(call.remoteNumber().value(), k -> new ArrayList<>()).add(call);
        }
        return byNumber.values().stream().map(CallHistoryRollup::summarize).toList();
    }

    /** Summarize one number's calls (newest first, non-empty). */
    public static Summary summarize(List<Call> groupNewestFirst) {
        if (groupNewestFirst.isEmpty()) {
            throw new IllegalArgumentException("group must not be empty");
        }
        final Call newest = groupNewestFirst.get(0);
        final CallOutcome lastOutcome = CallOutcome.classify(newest.disposition(), isConnected(newest));
        final boolean dnc = groupNewestFirst.stream()
                .anyMatch(c -> c.disposition().map(d -> d instanceof CallDisposition.DNC).orElse(false));
        final Optional<CallDisposition> badge = groupNewestFirst.stream()
                .map(Call::disposition)
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(CallHistoryRollup::valueRank));
        final Optional<Instant> callbackDue = groupNewestFirst.stream()
                .map(Call::disposition)
                .flatMap(Optional::stream)
                .filter(d -> d instanceof CallDisposition.Callback)
                .map(d -> ((CallDisposition.Callback) d).scheduledAt())
                .max(Comparator.naturalOrder());
        return new Summary(newest.remoteNumber().value(), newest.startedAt(), groupNewestFirst.size(),
                badge, lastOutcome, callbackDue, dnc);
    }

    private static boolean isConnected(Call call) {
        return call.answeredAt().isPresent() || call.durationMs().map(ms -> ms > 0).orElse(false);
    }

    /** Lower rank = more valuable (wins the badge). */
    private static int valueRank(CallDisposition disposition) {
        return switch (disposition) {
            case CallDisposition.Interested ignored    -> 0;
            case CallDisposition.Callback ignored      -> 1;
            case CallDisposition.Voicemail ignored     -> 2;
            case CallDisposition.NotInterested ignored -> 3;
            case CallDisposition.Busy ignored          -> 4;
            case CallDisposition.NoAnswer ignored      -> 5;
            case CallDisposition.Failed ignored        -> 6;
            case CallDisposition.DNC ignored           -> 7;
        };
    }
}
