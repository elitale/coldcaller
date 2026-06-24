package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A rendered conversation-list row: the most recent {@link SmsMessage} of a thread plus the
 * resolved lead identity (name / company), country (flag + local time), and triage flags. Built
 * off the FX thread in the controller; the cell is pure view. Mirrors {@code CallHistoryRow}.
 */
public record SmsConversationRow(
        SmsMessage last,
        Optional<Lead> lead,
        Optional<Country> country,
        boolean unread,
        boolean optedOut) {

    public SmsConversationRow {
        Objects.requireNonNull(last, "last must not be null");
        Objects.requireNonNull(lead, "lead must not be null");
        Objects.requireNonNull(country, "country must not be null");
    }

    /** The remote party's number — the conversation key. */
    public PhoneNumber remote() {
        return last.remoteNumber();
    }

    /** Lead's display name, else the raw remote number. */
    public String displayName() {
        return lead.map(Lead::displayName)
                .filter(s -> !s.isBlank())
                .orElseGet(() -> last.remoteNumber().value());
    }

    /** Lead's company, if known and non-blank. */
    public Optional<String> company() {
        return lead.flatMap(Lead::company).filter(s -> !s.isBlank());
    }

    /** Last message body (the list preview). */
    public String preview() {
        return last.body();
    }

    public CallDirection direction() {
        return last.direction();
    }

    public SmsStatus status() {
        return last.status();
    }

    public Instant sentAt() {
        return last.sentAt();
    }

    /** Triage state — UNREAD / NEEDS_REPLY / DONE — driving the "who's waiting on me?" view. */
    public ConversationReplyState replyState() {
        return ConversationReplyState.classify(last.direction(), !unread, optedOut);
    }
}
