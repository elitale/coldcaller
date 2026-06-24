package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationReplyStateTest {

    @Test
    void inboundLast_needsReply() {
        assertThat(ConversationReplyState.classify(CallDirection.INBOUND, false))
                .isEqualTo(ConversationReplyState.NEEDS_REPLY);
    }

    @Test
    void outboundLast_isDone() {
        assertThat(ConversationReplyState.classify(CallDirection.OUTBOUND, false))
                .isEqualTo(ConversationReplyState.DONE);
    }

    @Test
    void optedOut_isDone_evenWhenInboundLast() {
        assertThat(ConversationReplyState.classify(CallDirection.INBOUND, true))
                .isEqualTo(ConversationReplyState.DONE);
    }

    @Test
    void unseenInbound_isUnread_seenInbound_isNeedsReply() {
        assertThat(ConversationReplyState.classify(CallDirection.INBOUND, false, false))
                .isEqualTo(ConversationReplyState.UNREAD);
        // opening the thread (seen) clears UNREAD but NOT the still-owed reply
        assertThat(ConversationReplyState.classify(CallDirection.INBOUND, true, false))
                .isEqualTo(ConversationReplyState.NEEDS_REPLY);
    }

    @Test
    void threeArg_outboundOrOptedOut_isDone() {
        assertThat(ConversationReplyState.classify(CallDirection.OUTBOUND, false, false))
                .isEqualTo(ConversationReplyState.DONE);
        assertThat(ConversationReplyState.classify(CallDirection.INBOUND, false, true))
                .isEqualTo(ConversationReplyState.DONE);
    }

    @Test
    void needsReply_andUnread_predicates() {
        assertThat(ConversationReplyState.UNREAD.needsReply()).isTrue();
        assertThat(ConversationReplyState.UNREAD.unread()).isTrue();
        assertThat(ConversationReplyState.NEEDS_REPLY.needsReply()).isTrue();
        assertThat(ConversationReplyState.NEEDS_REPLY.unread()).isFalse();
        assertThat(ConversationReplyState.DONE.needsReply()).isFalse();
        assertThat(ConversationReplyState.DONE.unread()).isFalse();
    }
}
