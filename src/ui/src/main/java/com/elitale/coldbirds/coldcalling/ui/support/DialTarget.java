package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.Objects;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.ListProgress;

/**
 * A selectable dial target in the Power Dialer: either the synthetic <strong>All Leads</strong>
 * pool (every lead, no saved progress) or one saved {@link CallList}. Mirrors the Leads screen's
 * virtual "All Leads" so the dialer is usable out of the box, before any list is built.
 *
 * <p>Pure value (no JavaFX) — the readiness/label logic is unit-tested without a UI.
 */
public sealed interface DialTarget permits DialTarget.AllLeads, DialTarget.OneList {

    /** Every lead, dialed top to bottom. Carries only a live count — no persisted dial status. */
    record AllLeads(int leadCount) implements DialTarget {
        public AllLeads {
            if (leadCount < 0) throw new IllegalArgumentException("leadCount must be >= 0");
        }
    }

    /** One saved list, with its per-entry dial progress (supports resume). */
    record OneList(CallList list) implements DialTarget {
        public OneList {
            Objects.requireNonNull(list, "list must not be null");
        }
    }

    /** Idle-screen title for this target. */
    default String title() {
        return switch (this) {
            case AllLeads ignored -> "All Leads";
            case OneList o        -> o.list().name();
        };
    }

    /** Dial progress driving the Start/Resume readiness. */
    default ListProgress progress() {
        return switch (this) {
            case AllLeads a -> ListProgress.allPending(a.leadCount());
            case OneList o  -> ListProgress.of(o.list());
        };
    }

    /** Idle-screen readiness (button label, status line, enablement). */
    default PowerDialerReadiness readiness() {
        return PowerDialerReadiness.of(progress());
    }

    /** One-line label for the selector cell: {@code "{title}  —  {summary}"}. */
    default String selectorLabel() {
        return title() + "  —  " + summary();
    }

    private String summary() {
        final ListProgress p = progress();
        if (p.isEmpty())    return "no leads";
        if (p.isComplete()) return "all dialed";
        return switch (this) {
            case AllLeads ignored -> p.total() + " leads";
            case OneList ignored  -> p.pending() + " of " + p.total() + " left";
        };
    }
}
