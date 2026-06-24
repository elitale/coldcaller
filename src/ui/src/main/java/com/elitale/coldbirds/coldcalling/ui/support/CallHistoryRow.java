package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.util.Objects;
import java.util.Optional;

/**
 * A rendered Call History row: the pure {@link CallHistoryRollup.Summary} plus the resolved
 * lead, country (flag + local time), and "dialed from" label (multi-number accounts only).
 * Resolution happens off the FX thread in the controller.
 */
public record CallHistoryRow(
        CallHistoryRollup.Summary summary,
        Optional<Lead> lead,
        Optional<Country> country,
        Optional<String> dialedFromLabel) {

    public CallHistoryRow {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(lead, "lead must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(dialedFromLabel, "dialedFromLabel must not be null");
    }

    public String number() {
        return summary.number();
    }

    /** Display name: the lead's name, else the raw number. */
    public String displayName() {
        return lead.map(Lead::displayName).filter(s -> !s.isBlank()).orElse(summary.number());
    }

    public Optional<String> company() {
        return lead.flatMap(Lead::company).filter(s -> !s.isBlank());
    }
}
