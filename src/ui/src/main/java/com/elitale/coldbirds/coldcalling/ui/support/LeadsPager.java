package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.Cursor;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Headless accumulator for keyset-paged lead loading.
 *
 * <p>Owns the cursor-free base {@link LeadFilter}, the rows loaded so far, and the
 * cursor for the next page. The controller calls {@link #reset(LeadFilter)} when the
 * filter changes, asks {@link #nextRequest()} for the filter to fetch, hands each
 * loaded {@link Page} back via {@link #accept(Page)}, and reads {@link #rows()}.
 *
 * <p>Pure logic — no JavaFX — so the paging/cursor/append behaviour is unit-testable.
 * Not thread-safe; the controller mutates it only on the FX Application Thread.
 */
public final class LeadsPager {

    private LeadFilter base = LeadFilter.all();
    private final List<Lead> rows = new ArrayList<>();
    private List<Lead> lastPageRows = List.of();
    private Optional<Cursor> nextCursor = Optional.empty();
    private int total = 0;
    private boolean firstPageLoaded = false;

    /**
     * Begin a fresh query. Clears loaded rows and the cursor and adopts {@code base}
     * as the (cursor-free) filter for the first page.
     */
    public void reset(LeadFilter base) {
        this.base = Objects.requireNonNull(base, "base must not be null");
        this.rows.clear();
        this.lastPageRows = List.of();
        this.nextCursor = Optional.empty();
        this.total = 0;
        this.firstPageLoaded = false;
    }

    /** The filter to request next: the base for the first page, base + cursor thereafter. */
    public LeadFilter nextRequest() {
        return nextCursor.map(base::withCursor).orElse(base);
    }

    /** True once a page has been loaded and the repository reported a further page. */
    public boolean hasMore() {
        return nextCursor.isPresent();
    }

    /** True once at least one page has been accepted since the last {@link #reset}. */
    public boolean isFirstPageLoaded() {
        return firstPageLoaded;
    }

    /** Append a freshly loaded page, advancing the cursor and the filtered total. */
    public void accept(Page<Lead> page) {
        Objects.requireNonNull(page, "page must not be null");
        this.lastPageRows = List.copyOf(page.rows());
        this.rows.addAll(page.rows());
        this.nextCursor = page.nextCursor();
        this.total = page.total();
        this.firstPageLoaded = true;
    }

    /** Rows of the most recently accepted page — append these to the table view. */
    public List<Lead> lastPageRows() {
        return lastPageRows;
    }

    /** All rows accumulated across every accepted page. */
    public List<Lead> rows() {
        return List.copyOf(rows);
    }

    /** Number of rows loaded so far across all pages. */
    public int loadedCount() {
        return rows.size();
    }

    /** Total rows matching the filter, ignoring paging (from the last accepted page). */
    public int total() {
        return total;
    }
}
