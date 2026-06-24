package com.elitale.coldbirds.coldcalling.services.imports;

import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer;
import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer.Empty;
import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer.NeedsReview;
import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer.Normalized;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one raw CSV row against a {@link ColumnMapping}: extracts the known
 * fields, normalizes the primary phone, and preserves secondary numbers, the
 * extension, and unmapped columns as custom fields. Produces a {@link PreviewRow}
 * with a <em>provisional</em> status (VALID / NEEDS_REVIEW / EMPTY); duplicate and
 * DNC marking is a later cross-row pass in {@link LeadImportService}.
 */
public final class RowResolver {

    private final PhoneNormalizer normalizer;

    public RowResolver(PhoneNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
    }

    public PreviewRow resolve(int sourceLine, List<String> rawValues, ColumnMapping mapping,
                       Optional<String> defaultCountry) {
        Optional<String> firstName = Optional.empty();
        Optional<String> lastName = Optional.empty();
        Optional<String> company = Optional.empty();
        Optional<String> title = Optional.empty();
        Optional<String> email = Optional.empty();
        final Map<String, String> custom = new LinkedHashMap<>();

        final List<String> headers = mapping.headers();
        for (int col = 0; col < headers.size(); col++) {
            final String header = headers.get(col);
            final String value = col < rawValues.size() ? rawValues.get(col).strip() : "";
            if (value.isEmpty()) {
                continue;
            }
            switch (mapping.fieldOf(header)) {
                case FIRST_NAME -> firstName = firstName.or(() -> Optional.of(value));
                case LAST_NAME -> lastName = lastName.or(() -> Optional.of(value));
                case COMPANY -> company = company.or(() -> Optional.of(value));
                case TITLE -> title = title.or(() -> Optional.of(value));
                case EMAIL -> email = email.or(() -> Optional.of(value));
                case PHONE -> {
                    if (!mapping.isPrimaryPhone(header)) {
                        custom.put(header, normalizedOrRaw(value, defaultCountry));
                    }
                }
                case CUSTOM -> custom.put(header, value);
                case IGNORE -> { /* dropped */ }
            }
        }

        final String primaryRaw = mapping.primaryPhoneHeader()
                .map(h -> valueOf(headers, rawValues, h))
                .orElse("");
        final PhoneNormalizer.Outcome outcome = normalizer.normalize(primaryRaw, defaultCountry);

        return switch (outcome) {
            case Empty ignored -> row(sourceLine, rawValues, ImportRowStatus.EMPTY,
                    Optional.empty(), Optional.empty(), false,
                    firstName, lastName, company, title, email, custom);
            case NeedsReview nr -> row(sourceLine, rawValues, ImportRowStatus.NEEDS_REVIEW,
                    Optional.of(nr.reason()), Optional.empty(), false,
                    firstName, lastName, company, title, email, custom);
            case Normalized n -> {
                n.ext().ifPresent(ext -> custom.put("ext", ext));
                yield row(sourceLine, rawValues, ImportRowStatus.VALID,
                        Optional.empty(), Optional.of(n.e164()), n.assumedCountry(),
                        firstName, lastName, company, title, email, custom);
            }
        };
    }

    private String normalizedOrRaw(String value, Optional<String> defaultCountry) {
        return normalizer.normalize(value, defaultCountry) instanceof Normalized n
                ? n.e164().value()
                : value;
    }

    private static String valueOf(List<String> headers, List<String> values, String header) {
        final int idx = headers.indexOf(header);
        return idx >= 0 && idx < values.size() ? values.get(idx).strip() : "";
    }

    private static PreviewRow row(
            int sourceLine, List<String> rawValues, ImportRowStatus status,
            Optional<String> reason, Optional<com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber> phone,
            boolean assumed, Optional<String> firstName, Optional<String> lastName,
            Optional<String> company, Optional<String> title, Optional<String> email,
            Map<String, String> custom) {
        return new PreviewRow(sourceLine, rawValues, status, reason, phone, assumed,
                firstName, lastName, company, title, email, custom);
    }
}
