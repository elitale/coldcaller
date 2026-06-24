package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer.Outcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-driven contract for {@link PhoneNormalizer} — the make-or-break of the
 * import pipeline. Three tiers: silent (identity-preserving), flagged-assumption,
 * and never-silent (review / empty). Regex is banned; libphonenumber does the work.
 */
final class PhoneNormalizerTest {

    private final PhoneNormalizer normalizer = new PhoneNormalizer();

    private static final Optional<String> US = Optional.of("US");
    private static final Optional<String> NONE = Optional.empty();

    // ── Tier 1: silent (international input, no assumption) ────────────────────

    @Test
    void e164PassesThroughUntouched() {
        Outcome out = normalizer.normalize("+14155551234", NONE);
        assertThat(out).isInstanceOf(PhoneNormalizer.Normalized.class);
        PhoneNormalizer.Normalized n = (PhoneNormalizer.Normalized) out;
        assertThat(n.e164().value()).isEqualTo("+14155551234");
        assertThat(n.ext()).isEmpty();
        assertThat(n.assumedCountry()).isFalse();
    }

    @Test
    void stripsFormattingPunctuationAndTelPrefix() {
        assertThat(e164Of(normalizer.normalize("+1 (415) 555-1234", NONE))).isEqualTo("+14155551234");
        assertThat(e164Of(normalizer.normalize("tel:+1-415-555-1234", NONE))).isEqualTo("+14155551234");
    }

    @Test
    void leadingDoubleZeroBecomesPlus() {
        assertThat(e164Of(normalizer.normalize("0014155551234", NONE))).isEqualTo("+14155551234");
    }

    // ── Tier 2: flagged assumption (bare digits + default country) ────────────

    @Test
    void bareLocalWithDefaultCountryIsAssumed() {
        Outcome out = normalizer.normalize("(415) 555-1234", US);
        assertThat(out).isInstanceOf(PhoneNormalizer.Normalized.class);
        PhoneNormalizer.Normalized n = (PhoneNormalizer.Normalized) out;
        assertThat(n.e164().value()).isEqualTo("+14155551234");
        assertThat(n.assumedCountry()).isTrue();
    }

    @Test
    void elevenDigitLeadingOneWithDefaultCountry() {
        assertThat(e164Of(normalizer.normalize("14155551234", US))).isEqualTo("+14155551234");
    }

    // ── Extensions: preserved, never jammed into the dial string ──────────────

    @Test
    void extensionIsExtractedAndPreserved() {
        Outcome out = normalizer.normalize("+1 415 555 1234 x204", NONE);
        assertThat(out).isInstanceOf(PhoneNormalizer.Normalized.class);
        PhoneNormalizer.Normalized n = (PhoneNormalizer.Normalized) out;
        assertThat(n.e164().value()).isEqualTo("+14155551234");
        assertThat(n.ext()).contains("204");
    }

    // ── Tier 3a: empty / sentinel → Empty (skip row) ──────────────────────────

    @Test
    void blankAndSentinelValuesAreEmpty() {
        assertThat(normalizer.normalize(null, US)).isInstanceOf(PhoneNormalizer.Empty.class);
        assertThat(normalizer.normalize("", US)).isInstanceOf(PhoneNormalizer.Empty.class);
        assertThat(normalizer.normalize("   ", US)).isInstanceOf(PhoneNormalizer.Empty.class);
        assertThat(normalizer.normalize("N/A", US)).isInstanceOf(PhoneNormalizer.Empty.class);
        assertThat(normalizer.normalize("-", US)).isInstanceOf(PhoneNormalizer.Empty.class);
    }

    // ── Tier 3b: never silent → review ────────────────────────────────────────

    @Test
    void bareLocalWithoutDefaultCountryNeedsReview() {
        Outcome out = normalizer.normalize("4155551234", NONE);
        assertThat(out).isInstanceOf(PhoneNormalizer.NeedsReview.class);
        assertThat(((PhoneNormalizer.NeedsReview) out).reason()).isNotBlank();
    }

    @Test
    void garbageAndTooShortNeedReview() {
        assertThat(normalizer.normalize("abcdef", US)).isInstanceOf(PhoneNormalizer.NeedsReview.class);
        assertThat(normalizer.normalize("123", US)).isInstanceOf(PhoneNormalizer.NeedsReview.class);
        assertThat(normalizer.normalize("555.1234", US)).isInstanceOf(PhoneNormalizer.NeedsReview.class);
    }

    private static String e164Of(Outcome out) {
        return ((PhoneNormalizer.Normalized) out).e164().value();
    }
}
