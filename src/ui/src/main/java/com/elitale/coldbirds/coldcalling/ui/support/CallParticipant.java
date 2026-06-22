package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.util.Objects;
import java.util.Optional;

/**
 * The remote party shown on the calling screen: their E.164 number plus any
 * resolved contact identity (name, company/title) and country (for flag and
 * local-time display), and the notes/disposition carried over from the most
 * recent prior call to this number (so a re-dial pre-loads prior context).
 * Immutable; built off the FX thread and rendered on it.
 */
public record CallParticipant(
        String number,
        Optional<String> name,
        Optional<String> subtitle,
        Optional<Country> country,
        Optional<String> priorNotes,
        Optional<CallDisposition> priorDisposition) {

    public CallParticipant {
        Objects.requireNonNull(number, "number must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(subtitle, "subtitle must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(priorNotes, "priorNotes must not be null");
        Objects.requireNonNull(priorDisposition, "priorDisposition must not be null");
    }

    /**
     * Build a participant from a raw number and any resolved contact/country.
     * The display name falls back to the number; the subtitle combines title
     * and company when a contact is known. No prior-call context is attached.
     */
    public static CallParticipant of(String number, Optional<Contact> contact, Optional<Country> country) {
        return of(number, contact, country, Optional.empty());
    }

    /**
     * Build a participant, pre-loading notes and disposition from the most
     * recent prior call to this number (if any) so a re-dial shows that context.
     */
    public static CallParticipant of(String number, Optional<Contact> contact, Optional<Country> country,
                                     Optional<Call> priorCall) {
        Objects.requireNonNull(number, "number must not be null");
        Objects.requireNonNull(priorCall, "priorCall must not be null");
        final Optional<String> name = contact
                .map(Contact::displayName)
                .filter(n -> !n.equals(number) && !n.isBlank());
        final Optional<String> subtitle = contact.flatMap(CallParticipant::subtitleFor);
        final Optional<String> priorNotes = priorCall.flatMap(Call::notes).filter(n -> !n.isBlank());
        final Optional<CallDisposition> priorDisposition = priorCall.flatMap(Call::disposition);
        return new CallParticipant(number, name, subtitle, country, priorNotes, priorDisposition);
    }


    /** The headline label — contact name when known, otherwise the number. */
    public String headline() {
        return name.orElse(number);
    }

    /** Two-letter initials for the avatar, derived from the headline. */
    public String initials() {
        final String source = name.orElse(number).strip();
        if (source.isEmpty()) return "?";
        final String[] parts = source.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        final char first = source.charAt(0);
        return Character.isLetter(first) ? String.valueOf(Character.toUpperCase(first)) : "#";
    }

    private static Optional<String> subtitleFor(Contact contact) {
        final String title = contact.title().orElse("").strip();
        final String company = contact.company().orElse("").strip();
        final String joined = (!title.isEmpty() && !company.isEmpty())
                ? title + " · " + company
                : title + company;
        return joined.isBlank() ? Optional.empty() : Optional.of(joined);
    }
}
