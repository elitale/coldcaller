package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SmsConversationRowTest {

    private static final PhoneNumber REMOTE = new PhoneNumber("+12025551002");

    @Test
    void displayName_usesLeadName_elseNumber() {
        SmsConversationRow withLead = row(CallDirection.INBOUND, lead("Dave", "Smith", "Acme"), false, false);
        assertThat(withLead.displayName()).isEqualTo("Dave Smith");

        SmsConversationRow noLead = row(CallDirection.INBOUND, Optional.empty(), false, false);
        assertThat(noLead.displayName()).isEqualTo(REMOTE.value());
    }

    @Test
    void company_presentOnlyWhenLeadHasIt() {
        assertThat(row(CallDirection.INBOUND, lead("Dave", "Smith", "Acme"), false, false).company())
                .contains("Acme");
        assertThat(row(CallDirection.INBOUND, lead("Dave", "Smith", null), false, false).company())
                .isEmpty();
    }

    @Test
    void replyState_inboundUnread_isUnread() {
        assertThat(row(CallDirection.INBOUND, Optional.empty(), true, false).replyState())
                .isEqualTo(ConversationReplyState.UNREAD);
    }

    @Test
    void replyState_inboundRead_isNeedsReply() {
        assertThat(row(CallDirection.INBOUND, Optional.empty(), false, false).replyState())
                .isEqualTo(ConversationReplyState.NEEDS_REPLY);
    }

    @Test
    void replyState_outbound_isDone() {
        assertThat(row(CallDirection.OUTBOUND, Optional.empty(), false, false).replyState())
                .isEqualTo(ConversationReplyState.DONE);
    }

    @Test
    void replyState_optedOut_isDone() {
        assertThat(row(CallDirection.INBOUND, Optional.empty(), true, true).replyState())
                .isEqualTo(ConversationReplyState.DONE);
    }

    @Test
    void exposesLastMessageFields() {
        SmsConversationRow row = row(CallDirection.INBOUND, Optional.empty(), false, false);
        assertThat(row.remote()).isEqualTo(REMOTE);
        assertThat(row.preview()).isEqualTo("hi there");
        assertThat(row.direction()).isEqualTo(CallDirection.INBOUND);
        assertThat(row.status()).isInstanceOf(SmsStatus.Delivered.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static SmsConversationRow row(CallDirection dir, Optional<Lead> lead,
                                          boolean unread, boolean optedOut) {
        SmsMessage last = new SmsMessage(
                new SmsId(1L), dir, new PhoneNumberId(1L), Optional.empty(),
                REMOTE, "hi there", new SmsStatus.Delivered(),
                Instant.now(), Instant.now(), Instant.now());
        return new SmsConversationRow(last, lead, Optional.empty(), unread, optedOut);
    }

    private static Optional<Lead> lead(String first, String last, String company) {
        return Optional.of(new Lead(
                new LeadId(1L), Optional.of(first), Optional.of(last), REMOTE,
                Optional.ofNullable(company), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), false, Map.of(), LeadStatus.NEW,
                Instant.now(), Instant.now()));
    }
}
