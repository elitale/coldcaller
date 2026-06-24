package com.elitale.coldbirds.coldcalling.services.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Auto-detects a {@link ColumnMapping} from headers + sample rows: header
 * fuzzy-match first, then content sniffing (a column that is mostly phone- or
 * email-shaped IS that field even if the header lies). The dialer primary is
 * chosen by preference Mobile &gt; Direct &gt; Work &gt; first — never Corporate/HQ.
 */
public final class ColumnAutoDetector {

    private static final double CONTENT_THRESHOLD = 0.6;

    private ColumnAutoDetector() {}

    public static ColumnMapping detect(List<String> headers, List<List<String>> sampleRows) {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(sampleRows, "sampleRows must not be null");

        final Map<String, LeadField> fields = new LinkedHashMap<>();
        for (int col = 0; col < headers.size(); col++) {
            final String header = headers.get(col);
            fields.put(header, classify(header, columnValues(sampleRows, col)));
        }
        final Optional<String> primary = pickPrimaryPhone(headers, fields);
        return new ColumnMapping(headers, fields, primary);
    }

    private static LeadField classify(String header, List<String> values) {
        final String h = header.toLowerCase(Locale.ROOT).strip();
        if (h.contains("first")) return LeadField.FIRST_NAME;
        if (h.contains("last") || h.contains("surname")) return LeadField.LAST_NAME;
        if (h.contains("email") || h.contains("e-mail")) return LeadField.EMAIL;
        if (h.contains("company") || h.contains("organization") || h.contains("organisation")
                || h.contains("account") || h.contains("employer")) return LeadField.COMPANY;
        if (h.contains("title") || h.contains("position") || h.contains("job") || h.contains("role")) {
            return LeadField.TITLE;
        }
        if (h.contains("phone") || h.contains("mobile") || h.contains("cell")
                || h.contains("tel") || h.contains("direct")) return LeadField.PHONE;
        // Header gave no signal — sniff the content.
        if (fraction(values, ColumnAutoDetector::looksLikePhone) >= CONTENT_THRESHOLD) {
            return LeadField.PHONE;
        }
        if (fraction(values, v -> v.contains("@")) >= CONTENT_THRESHOLD) {
            return LeadField.EMAIL;
        }
        return LeadField.CUSTOM;
    }

    private static Optional<String> pickPrimaryPhone(List<String> headers, Map<String, LeadField> fields) {
        final List<String> phones = headers.stream()
                .filter(h -> fields.get(h) == LeadField.PHONE)
                .toList();
        if (phones.isEmpty()) {
            return Optional.empty();
        }
        // Preference order; never pick a corporate/HQ switchboard as the dialer number.
        for (String keyword : List.of("mobile", "cell", "direct", "work")) {
            final Optional<String> match = phones.stream()
                    .filter(h -> !isCorporate(h))
                    .filter(h -> h.toLowerCase(Locale.ROOT).contains(keyword))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return phones.stream().filter(h -> !isCorporate(h)).findFirst()
                .or(() -> Optional.of(phones.get(0)));
    }

    private static boolean isCorporate(String header) {
        final String h = header.toLowerCase(Locale.ROOT);
        return h.contains("corporate") || h.contains("hq") || h.contains("headquarter")
                || h.contains("company phone") || h.contains("switchboard");
    }

    private static List<String> columnValues(List<List<String>> rows, int col) {
        return rows.stream()
                .filter(r -> col < r.size())
                .map(r -> r.get(col) == null ? "" : r.get(col).strip())
                .filter(v -> !v.isEmpty())
                .toList();
    }

    private static double fraction(List<String> values, java.util.function.Predicate<String> p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        final long hits = values.stream().filter(p).count();
        return (double) hits / values.size();
    }

    private static boolean looksLikePhone(String value) {
        final long digits = value.chars().filter(Character::isDigit).count();
        return value.startsWith("+") ? digits >= 6 : digits >= 7 && digits <= 15
                && value.chars().allMatch(c -> Character.isDigit(c) || "+()-. ".indexOf(c) >= 0);
    }
}
