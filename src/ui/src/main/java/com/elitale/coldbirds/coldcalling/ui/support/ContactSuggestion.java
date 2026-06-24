package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;

import java.util.Objects;
import java.util.Optional;

/**
 * A row in the "New message" contact picker: either a matched {@link Lead} or a raw number with no
 * lead. Carries enough to render name + company + number + a DNC badge and to disambiguate "wrong
 * Dave" at a glance. Pure view-model.
 */
public record ContactSuggestion(Optional<Lead> lead, PhoneNumber number) {

    public ContactSuggestion {
        Objects.requireNonNull(lead, "lead must not be null");
        Objects.requireNonNull(number, "number must not be null");
    }

    public static ContactSuggestion ofLead(Lead lead) {
        Objects.requireNonNull(lead, "lead must not be null");
        return new ContactSuggestion(Optional.of(lead), lead.phone());
    }

    public static ContactSuggestion ofNumber(PhoneNumber number) {
        return new ContactSuggestion(Optional.empty(), number);
    }

    /** {@code true} when there is no matching lead — a bare-number "Text {n}" row. */
    public boolean isRawNumber() {
        return lead.isEmpty();
    }

    /** {@code true} when the matched lead is on the do-not-contact list. */
    public boolean dnc() {
        return lead.map(Lead::dnc).orElse(false);
    }

    /** Lead's display name, else the raw number. */
    public String displayName() {
        return lead.map(Lead::displayName)
                .filter(s -> !s.isBlank())
                .orElseGet(number::value);
    }

    /** Lead's company, if known and non-blank. */
    public Optional<String> company() {
        return lead.flatMap(Lead::company).filter(s -> !s.isBlank());
    }

    /** Secondary line: "Company · +1…", else just the number. */
    public String subtitle() {
        return company().map(c -> c + "  \u00B7  " + number.value()).orElseGet(number::value);
    }
}
