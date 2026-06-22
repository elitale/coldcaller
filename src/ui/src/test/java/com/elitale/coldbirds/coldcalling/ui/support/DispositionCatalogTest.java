package com.elitale.coldbirds.coldcalling.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class DispositionCatalogTest {

    private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void hasEightOrderedOptionsWithUniqueDigits() {
        assertThat(DispositionCatalog.ALL).hasSize(8);
        assertThat(DispositionCatalog.ALL).extracting(DispositionCatalog.Option::digit)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(DispositionCatalog.ALL).extracting(DispositionCatalog.Option::iconLiteral)
                .allMatch(literal -> literal.startsWith("bi-"));
    }

    @Test
    void mapsSimpleLabelsToDispositions() {
        assertThat(DispositionCatalog.toDisposition("Interested", NOW))
                .containsInstanceOf(CallDisposition.Interested.class);
        assertThat(DispositionCatalog.toDisposition("Not Interested", NOW))
                .containsInstanceOf(CallDisposition.NotInterested.class);
        assertThat(DispositionCatalog.toDisposition("Voicemail", NOW))
                .containsInstanceOf(CallDisposition.Voicemail.class);
        assertThat(DispositionCatalog.toDisposition("No Answer", NOW))
                .containsInstanceOf(CallDisposition.NoAnswer.class);
        assertThat(DispositionCatalog.toDisposition("Busy", NOW))
                .containsInstanceOf(CallDisposition.Busy.class);
        assertThat(DispositionCatalog.toDisposition("DNC", NOW))
                .containsInstanceOf(CallDisposition.DNC.class);
    }

    @Test
    void mapsIsCaseAndWhitespaceInsensitive() {
        assertThat(DispositionCatalog.toDisposition("  not INTERESTED ", NOW))
                .containsInstanceOf(CallDisposition.NotInterested.class);
    }

    @Test
    void callbackIsScheduledOneDayAhead() {
        CallDisposition d = DispositionCatalog.toDisposition("Callback", NOW).orElseThrow();
        assertThat(d).isInstanceOfSatisfying(CallDisposition.Callback.class, cb ->
                assertThat(cb.scheduledAt()).isEqualTo(NOW.plus(DispositionCatalog.DEFAULT_CALLBACK_DELAY)));
    }

    @Test
    void failedCarriesManualReason() {
        CallDisposition d = DispositionCatalog.toDisposition("Failed", NOW).orElseThrow();
        assertThat(d).isInstanceOfSatisfying(CallDisposition.Failed.class, f ->
                assertThat(f.reason()).isEqualTo("manual"));
    }

    @Test
    void unknownOrNullLabelMapsToEmpty() {
        assertThat(DispositionCatalog.toDisposition("nope", NOW)).isEmpty();
        assertThat(DispositionCatalog.toDisposition(null, NOW)).isEmpty();
    }

    @Test
    void rejectsNullNow() {
        assertThatNullPointerException()
                .isThrownBy(() -> DispositionCatalog.toDisposition("Interested", null));
    }

    @Test
    void everyCatalogLabelMaps() {
        DispositionCatalog.ALL.forEach(option ->
                assertThat(DispositionCatalog.toDisposition(option.label(), NOW))
                        .as("mapping for %s", option.label())
                        .isPresent());
    }
}
