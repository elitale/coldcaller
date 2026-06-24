package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.Objects;

import com.elitale.coldbirds.coldcalling.domain.model.ListProgress;

/**
 * Idle-screen presentation state for the Power Dialer, derived purely from a list's
 * {@link ListProgress}. Tells the controller what the primary button should say, whether it's
 * enabled, the one-line status, and whether to nudge the user toward the Leads screen.
 *
 * <p>Pure value (no JavaFX) so the start/resume/complete logic is unit-tested without a UI.
 */
public record PowerDialerReadiness(
        Kind kind,
        String primaryLabel,
        String statusLine,
        boolean primaryEnabled,
        boolean showBuildList) {

    /** What the selected list lets the user do right now. */
    public enum Kind {
        /** No leads at all — nothing to dial; point the user at Leads. */
        EMPTY,
        /** Fresh list, nothing dialed yet — Start. */
        READY,
        /** Partly dialed — Resume from the first pending lead. */
        RESUMABLE,
        /** Every lead dialed — nothing left to do. */
        COMPLETE
    }

    public PowerDialerReadiness {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(primaryLabel, "primaryLabel must not be null");
        Objects.requireNonNull(statusLine, "statusLine must not be null");
    }

    public static PowerDialerReadiness of(ListProgress p) {
        Objects.requireNonNull(p, "progress must not be null");
        if (p.isEmpty()) {
            return new PowerDialerReadiness(Kind.EMPTY, "Start dialing",
                    "No leads yet — add leads on the Leads screen", false, true);
        }
        if (p.isComplete()) {
            return new PowerDialerReadiness(Kind.COMPLETE, "List complete",
                    "All " + leads(p.total()) + " dialed", false, false);
        }
        if (p.isResumable()) {
            return new PowerDialerReadiness(Kind.RESUMABLE,
                    "Resume from #" + (p.resumeIndex() + 1),
                    leads(p.total()) + " · " + p.dialed() + " done · " + p.pending() + " left",
                    true, false);
        }
        return new PowerDialerReadiness(Kind.READY, "Start dialing",
                leads(p.total()) + " · ready to dial", true, false);
    }

    private static String leads(int n) {
        return n + (n == 1 ? " lead" : " leads");
    }
}
