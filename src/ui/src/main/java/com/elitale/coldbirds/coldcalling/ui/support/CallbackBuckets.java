package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits scheduled callbacks into <b>Overdue / Due today / Upcoming</b> for the pinned band, with
 * a timezone-aware "callable now" flag so a callback you legally/practically can't return yet (the
 * lead's local night) is de-emphasised rather than screamed as overdue.
 *
 * <p>Honored callbacks are dropped upstream (the service), so this only buckets open promises.
 * Pure and unit-tested — the core never-miss logic.
 */
public final class CallbackBuckets {

    private static final int CALL_START_HOUR = 8;   // 8am
    private static final int CALL_END_HOUR = 21;    // 9pm

    /** One open callback: who + when it was promised. */
    public record Item(String number, Optional<Lead> lead, Optional<Country> country, Instant scheduledAt) {
        public Item {
            Objects.requireNonNull(number, "number must not be null");
            Objects.requireNonNull(lead, "lead must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");
        }
    }

    /** A bucketed callback: the item, whether it's callable in the lead's timezone now, how late. */
    public record Entry(Item item, boolean callableNow, Duration overdueBy) {
        public Entry {
            Objects.requireNonNull(item, "item must not be null");
            Objects.requireNonNull(overdueBy, "overdueBy must not be null");
        }
    }

    /** The three buckets, each ordered for display. */
    public record Buckets(List<Entry> overdue, List<Entry> dueToday, List<Entry> upcoming) {
        public Buckets {
            overdue = List.copyOf(overdue);
            dueToday = List.copyOf(dueToday);
            upcoming = List.copyOf(upcoming);
        }

        public int total() {
            return overdue.size() + dueToday.size() + upcoming.size();
        }
    }

    private CallbackBuckets() {
    }

    public static Buckets bucket(List<Item> items, Instant now, ZoneId operatorZone) {
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(operatorZone, "operatorZone must not be null");

        final LocalDate todayLocal = now.atZone(operatorZone).toLocalDate();
        final List<Entry> overdue = new ArrayList<>();
        final List<Entry> dueToday = new ArrayList<>();
        final List<Entry> upcoming = new ArrayList<>();

        for (Item item : items) {
            final boolean callable = callableNow(item, now);
            if (!item.scheduledAt().isAfter(now)) {
                overdue.add(new Entry(item, callable, Duration.between(item.scheduledAt(), now)));
            } else {
                final LocalDate day = item.scheduledAt().atZone(operatorZone).toLocalDate();
                final Entry entry = new Entry(item, callable, Duration.ZERO);
                (day.equals(todayLocal) ? dueToday : upcoming).add(entry);
            }
        }

        // Overdue: callable-now first, then most-overdue first. Others: soonest first.
        overdue.sort(Comparator.comparing((Entry e) -> !e.callableNow())
                .thenComparing(e -> e.item().scheduledAt()));
        dueToday.sort(Comparator.comparing(e -> e.item().scheduledAt()));
        upcoming.sort(Comparator.comparing(e -> e.item().scheduledAt()));
        return new Buckets(overdue, dueToday, upcoming);
    }

    private static boolean callableNow(Item item, Instant now) {
        return item.country().map(country -> {
            final int hour = now.atZone(country.zone()).getHour();
            return hour >= CALL_START_HOUR && hour < CALL_END_HOUR;
        }).orElse(true);
    }
}
