package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Objects;
import java.util.Optional;

/**
 * Normalizes messy, real-world phone strings into strict E.164 using Google
 * libphonenumber — never hand-rolled regex (which silently corrupts extensions
 * and international numbers). Reused by the import pipeline and the inline grid's
 * live cell validation.
 *
 * <p>Three tiers: <b>silent</b> (international input → identity-preserving normalize),
 * <b>flagged assumption</b> (bare digits + a default country → normalize and mark
 * {@code assumedCountry}), and <b>never silent</b> (ambiguous → {@link NeedsReview};
 * blank/sentinel → {@link Empty}). Extensions are extracted and preserved, never
 * jammed into the dial string.
 */
public final class PhoneNormalizer {

    /** Result of normalizing one raw value. */
    public sealed interface Outcome permits Normalized, NeedsReview, Empty {}

    /** Successfully normalized to E.164. {@code assumedCountry} = a default region was applied. */
    public record Normalized(PhoneNumber e164, Optional<String> ext, boolean assumedCountry)
            implements Outcome {
        public Normalized {
            Objects.requireNonNull(e164, "e164 must not be null");
            Objects.requireNonNull(ext, "ext must not be null");
        }
    }

    /** Could not be normalized safely — goes to the review tray with a human reason. */
    public record NeedsReview(String reason) implements Outcome {
        public NeedsReview {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** Blank or sentinel (N/A, -, empty) — the row carries no phone. */
    public record Empty() implements Outcome {}

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    /**
     * Normalize a raw phone value.
     *
     * @param raw           the source cell value (may be null/blank/garbage)
     * @param defaultRegion ISO-3166 region (e.g. "US") applied to bare local numbers
     */
    public Outcome normalize(String raw, Optional<String> defaultRegion) {
        Objects.requireNonNull(defaultRegion, "defaultRegion must not be null");
        if (raw == null) {
            return new Empty();
        }
        final String cleaned = preClean(raw);
        if (cleaned.isEmpty() || isSentinel(cleaned)) {
            return new Empty();
        }

        final boolean international = cleaned.startsWith("+");
        final String region = international ? null : defaultRegion.orElse(null);
        if (!international && region == null) {
            return new NeedsReview("missing country code");
        }

        final Phonenumber.PhoneNumber parsed;
        try {
            parsed = util.parse(cleaned, region);
        } catch (NumberParseException e) {
            return new NeedsReview(reasonFor(e));
        }
        if (!util.isValidNumber(parsed)) {
            return new NeedsReview("not a valid phone number");
        }

        final PhoneNumber e164 = new PhoneNumber(util.format(parsed, PhoneNumberFormat.E164));
        final Optional<String> ext = parsed.hasExtension()
                ? Optional.of(parsed.getExtension())
                : Optional.empty();
        return new Normalized(e164, ext, !international);
    }

    /** Trim, drop a {@code tel:} scheme, normalize invisible whitespace, map leading 00 → +. */
    private static String preClean(String raw) {
        String s = raw.strip().replace('\u00A0', ' ').strip();
        if (s.regionMatches(true, 0, "tel:", 0, 4)) {
            s = s.substring(4).strip();
        }
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        }
        return s;
    }

    private static boolean isSentinel(String cleaned) {
        final String lower = cleaned.toLowerCase();
        return lower.equals("n/a") || lower.equals("na") || lower.equals("-")
                || lower.equals("none") || lower.equals("null");
    }

    private static String reasonFor(NumberParseException e) {
        return switch (e.getErrorType()) {
            case INVALID_COUNTRY_CODE -> "invalid country code";
            case NOT_A_NUMBER -> "not a phone number";
            case TOO_SHORT_AFTER_IDD, TOO_SHORT_NSN -> "too short";
            case TOO_LONG -> "too long";
        };
    }
}
