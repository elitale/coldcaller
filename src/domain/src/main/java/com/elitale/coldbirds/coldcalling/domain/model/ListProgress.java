package com.elitale.coldbirds.coldcalling.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Read-only projection of a {@link CallList}'s dial progress, derived from its
 * entries' {@link CallListEntry.DialStatus}. Pure value — no I/O.
 *
 * <p>{@code resumeIndex} is the index of the first {@code PENDING} entry (the lead a
 * Resume would dial next), or {@code -1} when nothing is pending.
 */
public record ListProgress(int total, int dialed, int pending, int resumeIndex) {

    public ListProgress {
        if (total < 0)   throw new IllegalArgumentException("total must be >= 0");
        if (dialed < 0)  throw new IllegalArgumentException("dialed must be >= 0");
        if (pending < 0) throw new IllegalArgumentException("pending must be >= 0");
    }

    /** Compute progress from a list's current entries. */
    public static ListProgress of(CallList list) {
        return of(Objects.requireNonNull(list, "list must not be null").entries());
    }

    /**
     * Progress for a not-yet-dialed pool of {@code total} leads — nothing dialed, every lead
     * pending. Models the synthetic "All Leads" target, which carries no persisted dial status.
     */
    public static ListProgress allPending(int total) {
        if (total < 0) throw new IllegalArgumentException("total must be >= 0");
        return new ListProgress(total, 0, total, total > 0 ? 0 : -1);
    }

    /** Compute progress from a snapshot of entries (dial order). */
    public static ListProgress of(List<CallListEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        int dialed = 0;
        int pending = 0;
        int resumeIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).status() == CallListEntry.DialStatus.PENDING) {
                pending++;
                if (resumeIndex < 0) resumeIndex = i;
            } else {
                dialed++;
            }
        }
        return new ListProgress(entries.size(), dialed, pending, resumeIndex);
    }

    /** No leads at all. */
    public boolean isEmpty() { return total == 0; }

    /** Has leads, none pending — every lead has been dialed. */
    public boolean isComplete() { return total > 0 && pending == 0; }

    /** Some dialed, some still pending — a resume is in order. */
    public boolean isResumable() { return dialed > 0 && pending > 0; }

    /** At least one pending lead remains to dial. */
    public boolean hasPending() { return pending > 0; }
}
