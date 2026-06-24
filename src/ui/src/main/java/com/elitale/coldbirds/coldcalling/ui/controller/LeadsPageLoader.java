package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.ui.support.LeadsPager;
import javafx.application.Platform;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Owns keyset infinite-scroll paging for the Leads table.
 *
 * <p>Holds the {@link LeadsPager}, loads pages off the FX thread via
 * {@link CompletableFuture#supplyAsync}, and re-enters the FX thread through
 * {@link Platform#runLater} to publish rows via the supplied callbacks. A generation
 * counter discards stale responses when a newer filter supersedes an in-flight request.
 */
final class LeadsPageLoader {

    private static final int PREFETCH_THRESHOLD = 5;

    private final LeadService service;
    private final Consumer<List<Lead>> appendRows;
    private final Runnable clearRows;
    private final Runnable onPageLoaded;
    private final Runnable onError;
    private final LeadsPager pager = new LeadsPager();

    private boolean loading = false;
    private int generation = 0;

    LeadsPageLoader(LeadService service,
                    Consumer<List<Lead>> appendRows,
                    Runnable clearRows,
                    Runnable onPageLoaded,
                    Runnable onError) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.appendRows = Objects.requireNonNull(appendRows, "appendRows must not be null");
        this.clearRows = Objects.requireNonNull(clearRows, "clearRows must not be null");
        this.onPageLoaded = Objects.requireNonNull(onPageLoaded, "onPageLoaded must not be null");
        this.onError = Objects.requireNonNull(onError, "onError must not be null");
    }

    /** Apply a new base filter: reset the pager, clear rows, and load the first page. */
    void apply(LeadFilter base) {
        generation++;
        pager.reset(base);
        clearRows.run();
        loadNext();
    }

    /** Row-realization hook — prefetch the next page as the user nears the end of the list. */
    void onRowRealized(int index, int loadedCount) {
        if (index >= 0 && index >= loadedCount - PREFETCH_THRESHOLD
                && pager.isFirstPageLoaded() && pager.hasMore() && !loading) {
            loadNext();
        }
    }

    int total() {
        return pager.total();
    }

    private void loadNext() {
        if (loading) {
            return;
        }
        if (pager.isFirstPageLoaded() && !pager.hasMore()) {
            return;
        }
        loading = true;
        final int requestGeneration = generation;
        final LeadFilter request = pager.nextRequest();
        CompletableFuture
                .supplyAsync(() -> service.findPage(request))
                .whenCompleteAsync((page, error) -> {
                    loading = false;
                    if (requestGeneration != generation) {
                        return; // a newer filter superseded this request
                    }
                    if (error != null) {
                        onError.run();
                        return;
                    }
                    pager.accept(page);
                    appendRows.accept(pager.lastPageRows());
                    onPageLoaded.run();
                }, Platform::runLater);
    }
}
