package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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

    /** A single label:value row in the collapsed "all fields" tier. */
    public record Field(String label, String value) {
        public Field {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(value, "value must not be null");
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
                .ifPresent(e -> detail.add(new Field("Email", e)));

        lead.customFields().entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .sorted((a, b) -> a.getKey().compareToIgnoreCase(b.getKey()))
                .forEach(e -> detail.add(new Field(e.getKey().trim(), e.getValue().trim())));

        lead.notes().map(String::trim).filter(s -> !s.isEmpty())
                .ifPresent(n -> detail.add(new Field("Notes", n)));

        detail.add(new Field("Added",
                ADDED_FMT.format(lead.createdAt().atZone(ZoneId.systemDefault()))));

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

    private static Optional<String> joinNonBlank(final Optional<String> a, final Optional<String> b) {
        final List<String> parts = Stream.of(a, b)
                .flatMap(Optional::stream)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", parts));
    }
}
