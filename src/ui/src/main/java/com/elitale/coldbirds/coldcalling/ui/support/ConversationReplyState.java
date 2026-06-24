package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;

/**
 * Triage state of a conversation, from the daily operator's question "who is waiting on me?".
 *
 * <p>The rule that keeps the screen honest: a thread is {@link #DONE} only when the last message is
 * <em>outbound</em> (I replied) or the lead opted out. An inbound-last thread is still owed a reply
 * — opening it clears the {@link #UNREAD} bold but it stays {@link #NEEDS_REPLY}, so a dropped
 * follow-up is never hidden by merely having looked at it. Pure.
 */
public enum ConversationReplyState {

    /** Inbound-last and not yet seen — bold in the list. */
    UNREAD,
    /** Inbound-last, seen but not yet replied to — still on me. */
    NEEDS_REPLY,
    /** I replied last, or the lead opted out — nothing owed. */
    DONE;

    /** {@code true} for anything still waiting on a reply (UNREAD or NEEDS_REPLY). */
    public boolean needsReply() {
        return this != DONE;
    }

    /** {@code true} only for an unseen inbound-last thread. */
    public boolean unread() {
        return this == UNREAD;
    }

    /**
     * Two-state classification (no seen/unseen tracking): NEEDS_REPLY when the last message is
     * inbound and the lead has not opted out, else DONE.
     */
    public static ConversationReplyState classify(CallDirection lastDirection, boolean optedOut) {
        if (optedOut) return DONE;
        return lastDirection == CallDirection.INBOUND ? NEEDS_REPLY : DONE;
    }

    /**
     * Three-state classification adding the unread tier: an inbound-last thread is UNREAD until
     * {@code seen}, then NEEDS_REPLY; outbound-last or opted-out is DONE.
     */
    public static ConversationReplyState classify(CallDirection lastDirection, boolean seen, boolean optedOut) {
        if (optedOut) return DONE;
        if (lastDirection != CallDirection.INBOUND) return DONE;
        return seen ? NEEDS_REPLY : UNREAD;
    }
}
