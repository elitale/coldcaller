package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The "recently contacted" shortlist shown when the New-message picker is empty — the people a rep
 * most recently called or texted, so the common "text someone I just dialed" case is zero-typing.
 * Merges calls + SMS, keeps one entry per number (its most-recent touch), newest-first, capped. Pure.
 */
public final class RecentContacts {

    private RecentContacts() {}

    public static List<PhoneNumber> recent(List<Call> calls, List<SmsMessage> messages, int cap) {
        Objects.requireNonNull(calls, "calls must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (cap <= 0) return List.of();

        final Map<String, PhoneNumber> byValue = new HashMap<>();
        final Map<String, Instant> latest = new HashMap<>();
        for (final Call c : calls) merge(byValue, latest, c.remoteNumber(), c.startedAt());
        for (final SmsMessage m : messages) merge(byValue, latest, m.remoteNumber(), m.sentAt());

        return latest.entrySet().stream()
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .limit(cap)
                .map(e -> byValue.get(e.getKey()))
                .toList();
    }

    private static void merge(Map<String, PhoneNumber> byValue, Map<String, Instant> latest,
                              PhoneNumber number, Instant at) {
        final String key = number.value();
        byValue.putIfAbsent(key, number);
        latest.merge(key, at, (existing, incoming) -> incoming.isAfter(existing) ? incoming : existing);
    }
}
