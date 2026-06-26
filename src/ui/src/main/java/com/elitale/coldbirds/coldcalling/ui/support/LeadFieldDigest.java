package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;

/**
 * Presentation-ready, tiered view of a {@link Lead} for the dialing context panel.
 *
 * <p>Splits a lead into the three buyer-validated tiers so the panel can show a
 * 3-second glance and disclose everything else progressively:
 * <ul>
 *   <li><b>Tier 1 (glance):</b> {@link #name()} + {@link #companyTitle()}.</li>
 *   <li><b>Tier 2 (context):</b> {@link #dnc()}, {@link #statusLabel()}, {@link #tags()}.</li>
 *   <li><b>Tier 3 (all fields, collapsed):</b> {@link #detailFields()} — email, every
 *       custom column (sorted), notes, and the added date.</li>
 * </ul>
 *
 * <p>Pure and headless (no JavaFX) so the tiering rules are unit-testable.
 */
public record LeadFieldDigest(
        String name,
        Optional<String> companyTitle,
        boolean dnc,
        String statusLabel,
        List<String> tags,
        List<Field> detailFields) {

    private static final DateTimeFormatter ADDED_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** ISO date/date-time values begin with {@code yyyy-MM-dd}; gate parsing on that. */
    private static final Pattern ISO_DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern EMAIL =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern IMAGE_LABEL = Pattern.compile(
            ".*(photo|image|avatar|picture|logo|thumbnail|headshot).*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IMAGE_EXT =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg");

    /** How a detail value should be rendered: plain text, a mailto, a web link, or an image. */
    public enum FieldKind { TEXT, EMAIL, LINK, IMAGE }

    /** A single label:value row in the collapsed "all fields" tier. */
    public record Field(String label, String value, FieldKind kind) {
        public Field {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    public LeadFieldDigest {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(companyTitle, "companyTitle must not be null");
        Objects.requireNonNull(statusLabel, "statusLabel must not be null");
        tags = List.copyOf(tags);
        detailFields = List.copyOf(detailFields);
    }

    /** Derive the tiered digest from a lead. */
    public static LeadFieldDigest of(final Lead lead) {
        Objects.requireNonNull(lead, "lead must not be null");

        final List<Field> detail = new ArrayList<>();
        lead.email().map(String::trim).filter(s -> !s.isEmpty())
                .ifPresent(e -> detail.add(new Field("Email", e, FieldKind.EMAIL)));

        lead.customFields().entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .sorted((a, b) -> a.getKey().compareToIgnoreCase(b.getKey()))
                .forEach(e -> detail.add(classify(e.getKey().trim(), e.getValue().trim())));

        lead.notes().map(String::trim).filter(s -> !s.isEmpty())
                .ifPresent(n -> detail.add(new Field("Notes", n, FieldKind.TEXT)));

        detail.add(new Field("Added",
                ADDED_FMT.format(lead.createdAt().atZone(ZoneId.systemDefault())), FieldKind.TEXT));

        return new LeadFieldDigest(
                lead.displayName(),
                joinNonBlank(lead.company(), lead.title()),
                lead.dnc(),
                LeadStatusLabel.of(orStatus(lead.leadStatus())),
                List.copyOf(lead.tags()),
                detail);
    }

    private static LeadStatus orStatus(final LeadStatus status) {
        return status == null ? LeadStatus.NEW : status;
    }

    /** Decide how a raw custom value should be displayed and rendered. */
    private static Field classify(final String label, final String value) {
        final Optional<String> humanizedDate = humanizeDate(value);
        if (humanizedDate.isPresent()) {
            return new Field(label, humanizedDate.get(), FieldKind.TEXT);
        }
        if (EMAIL.matcher(value).matches()) {
            return new Field(label, value, FieldKind.EMAIL);
        }
        if (isHttpUrl(value)) {
            return new Field(label, value, isImageUrl(label, value) ? FieldKind.IMAGE : FieldKind.LINK);
        }
        return new Field(label, value, FieldKind.TEXT);
    }

    /** Humanize an ISO instant/date-time/date to {@code d MMM yyyy}; empty if not a date. */
    private static Optional<String> humanizeDate(final String value) {
        if (!ISO_DATE_PREFIX.matcher(value).lookingAt()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ADDED_FMT.format(Instant.parse(value).atZone(ZoneId.systemDefault())));
        } catch (DateTimeParseException ignored) {
            // not an instant
        }
        try {
            return Optional.of(ADDED_FMT.format(
                    LocalDateTime.parse(value).atZone(ZoneId.systemDefault())));
        } catch (DateTimeParseException ignored) {
            // not a local date-time
        }
        try {
            return Optional.of(ADDED_FMT.format(LocalDate.parse(value)));
        } catch (DateTimeParseException ignored) {
            // not a local date
        }
        return Optional.empty();
    }

    private static boolean isHttpUrl(final String value) {
        final String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isImageUrl(final String label, final String url) {
        if (IMAGE_LABEL.matcher(label).matches()) {
            return true;
        }
        String path = url.toLowerCase(Locale.ROOT);
        final int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        final String stripped = path;
        return IMAGE_EXT.stream().anyMatch(stripped::endsWith);
    }

    private static Optional<String> joinNonBlank(final Optional<String> a, final Optional<String> b) {
        final List<String> parts = Stream.of(a, b)
                .flatMap(Optional::stream)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", parts));
    }
}
