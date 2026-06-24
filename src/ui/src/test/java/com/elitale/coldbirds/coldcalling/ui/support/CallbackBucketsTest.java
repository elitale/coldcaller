package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackBuckets.Buckets;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackBuckets.Item;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class CallbackBucketsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant NOW = Instant.parse("2026-06-24T10:00:00Z");
    // 10:00 UTC is 03:00 in Los Angeles (UTC-7 in summer) → outside business hours → not callable.
    private static final Country LA = new Country("US", "United States", "+1", "America/Los_Angeles");

    private static Item item(String number, Instant when, Optional<Country> country) {
        return new Item(number, Optional.empty(), country, when);
    }

    @Test
    void splitsPastTodayAndFuture() {
        Buckets b = CallbackBuckets.bucket(List.of(
                item("+10", NOW.minusSeconds(3600), Optional.empty()),   // overdue
                item("+11", NOW.plusSeconds(3600), Optional.empty()),    // later today
                item("+12", NOW.plusSeconds(8 * 86400), Optional.empty()) // next week
        ), NOW, UTC);

        assertThat(b.overdue()).extracting(e -> e.item().number()).containsExactly("+10");
        assertThat(b.dueToday()).extracting(e -> e.item().number()).containsExactly("+11");
        assertThat(b.upcoming()).extracting(e -> e.item().number()).containsExactly("+12");
        assertThat(b.total()).isEqualTo(3);
    }

    @Test
    void overdueByIsPositive() {
        Buckets b = CallbackBuckets.bucket(
                List.of(item("+10", NOW.minusSeconds(7200), Optional.empty())), NOW, UTC);
        assertThat(b.overdue().get(0).overdueBy().toHours()).isEqualTo(2);
    }

    @Test
    void timezoneMakesNightCallbackNotCallable() {
        Buckets b = CallbackBuckets.bucket(
                List.of(item("+1", NOW.minusSeconds(60), Optional.of(LA))), NOW, UTC);
        assertThat(b.overdue().get(0).callableNow()).isFalse();
    }

    @Test
    void noCountryIsCallable() {
        Buckets b = CallbackBuckets.bucket(
                List.of(item("+1", NOW.minusSeconds(60), Optional.empty())), NOW, UTC);
        assertThat(b.overdue().get(0).callableNow()).isTrue();
    }

    @Test
    void overdueOrdersCallableFirst() {
        Buckets b = CallbackBuckets.bucket(List.of(
                item("+night", NOW.minusSeconds(7200), Optional.of(LA)),  // earlier but not callable
                item("+day", NOW.minusSeconds(60), Optional.empty())      // later but callable
        ), NOW, UTC);
        assertThat(b.overdue()).extracting(e -> e.item().number()).containsExactly("+day", "+night");
    }
}
