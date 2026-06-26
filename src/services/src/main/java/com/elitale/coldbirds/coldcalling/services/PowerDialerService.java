package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.CallListEntry;
import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.ListProgress;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.PowerDialerSession;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PowerDialerState;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages power dialer sessions: iterates a {@link CallList}, dials each entry, and auto-advances
 * on unanswered calls.
 *
 * <p>Threading: all public control/query methods are {@code synchronized}. Scheduler callbacks
 * re-enter via {@code synchronized (this)}. UI callbacks fire while the lock is held — they must
 * not re-enter this service.
 */
public final class PowerDialerService {

  private static final Logger LOG = LoggerFactory.getLogger(PowerDialerService.class);

  /** Settings key: id of the most recently started list (auto-selected on open). */
  static final String KEY_LAST_LIST = "power_dialer.last_list_id";

  /**
   * Sentinel stored in {@link #KEY_LAST_LIST} when the synthetic "All Leads" pool was last dialed.
   */
  static final String ALL_LEADS_TOKEN = "all";

  /** Synthetic list id for the "All Leads" pool — never persisted to {@code call_lists}. */
  private static final CallListId ALL_LEADS_ID = new CallListId(Long.MAX_VALUE);

  /** Snapshot statistics for UI display. */
  public record SessionStats(int dialedCount, int connectedCount, int remaining) {}

  // ── Session state (only accessed while lock held) ─────────────────────────

  private static final class Session {
    final CallList callList;
    final boolean synthetic;
    int position;
    int dialedCount;
    int connectedCount;
    boolean currentCallAnswered;
    boolean currentCallConnected;
    ScheduledFuture<?> pendingTimeout;
    PowerDialerState state = new PowerDialerState.Running();
    final Instant startedAt = Instant.now();

    Session(CallList callList, boolean synthetic) {
      this.callList = callList;
      this.synthetic = synthetic;
    }

    boolean isExhausted() {
      return position >= callList.entries().size();
    }

    Optional<CallListEntry> currentEntry() {
      return position < callList.entries().size()
          ? Optional.of(callList.entries().get(position))
          : Optional.empty();
    }

    PowerDialerSession toRecord() {
      return new PowerDialerSession(
          0L,
          callList.id(),
          position,
          state,
          dialedCount,
          connectedCount,
          startedAt,
          Optional.empty());
    }

    SessionStats toStats() {
      return new SessionStats(
          dialedCount, connectedCount, Math.max(0, callList.entries().size() - position));
    }
  }

  // ── Dependencies ──────────────────────────────────────────────────────────

  private final CallListRepository callListRepo;
  private final LeadRepository leadRepo;
  private final CallerIdSelector callerIdSelector;
  private final SettingsService settings;
  private final BiConsumer<PhoneNumber, PhoneNumber> dialCommand;
  private final ScheduledExecutorService scheduler;

  // ── Mutable state (all guarded by this) ───────────────────────────────────

  private Session session;
  private Consumer<Optional<PowerDialerSession>> onSessionChangedCb = s -> {};
  private Consumer<Optional<Lead>> onLeadChangedCb = c -> {};
  private Consumer<SessionStats> onStatsCb = s -> {};

  // ── Constructors ──────────────────────────────────────────────────────────

  public PowerDialerService(
      CallListRepository callListRepo,
      LeadRepository leadRepo,
      CallerIdSelector callerIdSelector,
      SettingsService settings,
      BiConsumer<PhoneNumber, PhoneNumber> dialCommand) {
    this(
        callListRepo,
        leadRepo,
        callerIdSelector,
        settings,
        dialCommand,
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "power-dialer-scheduler");
              t.setDaemon(true);
              return t;
            }));
  }

  /** Package-private: for tests — supply a controllable scheduler. */
  PowerDialerService(
      CallListRepository callListRepo,
      LeadRepository leadRepo,
      CallerIdSelector callerIdSelector,
      SettingsService settings,
      BiConsumer<PhoneNumber, PhoneNumber> dialCommand,
      ScheduledExecutorService scheduler) {
    this.callListRepo = Objects.requireNonNull(callListRepo);
    this.leadRepo = Objects.requireNonNull(leadRepo);
    this.callerIdSelector = Objects.requireNonNull(callerIdSelector);
    this.settings = Objects.requireNonNull(settings);
    this.dialCommand = Objects.requireNonNull(dialCommand);
    this.scheduler = Objects.requireNonNull(scheduler);
  }

  // ── Callback registration ─────────────────────────────────────────────────

  public void setOnSessionChanged(Consumer<Optional<PowerDialerSession>> cb) {
    this.onSessionChangedCb = Objects.requireNonNull(cb);
  }

  public void setOnLeadChanged(Consumer<Optional<Lead>> cb) {
    this.onLeadChangedCb = Objects.requireNonNull(cb);
  }

  public void setOnStatsChanged(Consumer<SessionStats> cb) {
    this.onStatsCb = Objects.requireNonNull(cb);
  }

  // ── Control API ───────────────────────────────────────────────────────────

  /**
   * Start (or resume) dialing {@code listId}. The session begins at the first {@code PENDING}
   * entry, so a partially-dialed list resumes where it left off and never re-dials reached leads.
   * The list id is remembered as the last-used list.
   *
   * @return {@code Err} when a session is already running, the list is missing/empty, or every lead
   *     has already been dialed (nothing left to dial).
   */
  public synchronized Result<Void> start(CallListId listId) {
    if (session != null && session.state instanceof PowerDialerState.Running)
      return Result.err("A session is already running");
    final Optional<CallList> found = callListRepo.findById(Objects.requireNonNull(listId));
    if (found.isEmpty()) return Result.err("Call list not found: " + listId.value());
    final CallList list = found.get();
    if (list.entries().isEmpty())
      return Result.err("This list has no leads to dial — add leads on the Leads screen");
    final ListProgress progress = ListProgress.of(list);
    if (progress.resumeIndex() < 0) return Result.err("List complete — every lead has been dialed");
    session = new Session(list, false);
    session.position = progress.resumeIndex();
    settings.set(KEY_LAST_LIST, String.valueOf(listId.value()));
    fireSessionChanged();
    dialCurrent();
    return Result.ok(null);
  }

  /**
   * Start dialing every lead — the synthetic "All Leads" pool. Unlike a saved list this carries no
   * persisted dial status: it always starts at the first lead and is not resumed across restarts
   * (in-session pause/resume still works). Remembered as the last-used target so the pool is
   * re-selected on open.
   *
   * @return {@code Err} when a session is already running or there are no leads to dial.
   */
  public synchronized Result<Void> startAllLeads() {
    if (session != null && session.state instanceof PowerDialerState.Running)
      return Result.err("A session is already running");
    final List<Lead> leads = leadRepo.findAll();
    if (leads.isEmpty()) return Result.err("No leads to dial — add leads on the Leads screen");
    session = new Session(syntheticAllLeads(leads), true);
    settings.set(KEY_LAST_LIST, ALL_LEADS_TOKEN);
    fireSessionChanged();
    dialCurrent();
    return Result.ok(null);
  }

  public synchronized void pause() {
    if (session == null) return;
    cancelTimeout();
    session.state = new PowerDialerState.Paused();
    fireSessionChanged();
  }

  public synchronized void resume() {
    if (session == null || !(session.state instanceof PowerDialerState.Paused)) return;
    session.state = new PowerDialerState.Running();
    fireSessionChanged();
    if (!session.currentCallAnswered && session.pendingTimeout == null) dialCurrent();
  }

  public synchronized void stop() {
    if (session == null) return;
    cancelTimeout();
    session.state = new PowerDialerState.Stopped();
    fireSessionChanged();
    onLeadChangedCb.accept(Optional.empty());
    session = null;
  }

  /** Manually advance to the next lead after an answered call ends. */
  public synchronized void advance() {
    if (session == null || !(session.state instanceof PowerDialerState.Running)) return;
    cancelTimeout();
    session.position++;
    session.currentCallAnswered = false;
    if (session.isExhausted()) endSession();
    else {
      fireSessionChanged();
      dialCurrent();
    }
  }

  // ── Query API ─────────────────────────────────────────────────────────────

  public List<CallList> getCallLists() {
    return callListRepo.findAll();
  }

  /** Number of dialable leads in the synthetic "All Leads" pool. */
  public int countAllLeads() {
    return leadRepo.findAll().size();
  }

  /** Whether the synthetic "All Leads" pool was the last-used target — re-selected on open. */
  public boolean lastUsedAllLeads() {
    return ALL_LEADS_TOKEN.equals(settings.get(KEY_LAST_LIST, ""));
  }

  /** Id of the most recently started list, if any — used to auto-select on open. */
  public Optional<CallListId> lastUsedListId() {
    final String raw = settings.get(KEY_LAST_LIST, "");
    if (raw == null || raw.isBlank()) return Optional.empty();
    try {
      final long id = Long.parseLong(raw.trim());
      return id > 0 ? Optional.of(new CallListId(id)) : Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public synchronized Optional<PowerDialerSession> getCurrentSession() {
    return Optional.ofNullable(session).map(Session::toRecord);
  }

  public synchronized Optional<Lead> getCurrentLead() {
    if (session == null) return Optional.empty();
    return session.currentEntry().flatMap(e -> leadRepo.findById(e.leadId()));
  }

  public synchronized Optional<SessionStats> getStats() {
    return Optional.ofNullable(session).map(Session::toStats);
  }

  /**
   * The next {@code n} leads queued after the current one, in dial order, for the queue-preview
   * panel. Returns an empty list when no session is active, {@code n <= 0}, or the list is
   * exhausted; clamps to however many remain.
   *
   * @param n maximum number of upcoming leads to return
   */
  public synchronized List<Lead> upcoming(int n) {
    if (session == null || n <= 0) return List.of();
    final List<CallListEntry> entries = session.callList.entries();
    final List<Lead> result = new ArrayList<>();
    for (int i = session.position + 1; i < entries.size() && result.size() < n; i++) {
      leadRepo.findById(entries.get(i).leadId()).ifPresent(result::add);
    }
    return List.copyOf(result);
  }

  // ── Call event notifications (composed in ColdCallingApp) ─────────────────

  public synchronized void notifyCallAnswered(String callId) {
    if (session == null) return;
    // Count one connect per dialed call, and never more connects than dials. The guard
    // resets on the next dial (see dialCurrent), so a stray/duplicate "answered" — including
    // one that lands after the call ended but before manual advance — can't push
    // connectedCount past dialedCount, which would make PowerDialerSession throw every UI tick.
    if (!(session.state instanceof PowerDialerState.Running)) return;
    if (session.currentCallConnected) return;
    if (session.connectedCount >= session.dialedCount) return;
    cancelTimeout();
    session.currentCallAnswered = true;
    session.currentCallConnected = true;
    session.connectedCount++;
    onStatsCb.accept(session.toStats());
    fireSessionChanged();
  }

  public synchronized void notifyCallEnded(String callId, String reason) {
    if (session == null || !(session.state instanceof PowerDialerState.Running)) return;
    if (session.currentCallAnswered) {
      session.currentCallAnswered = false;
      markCurrentEntry(CallListEntry.DialStatus.DIALED);
    } else {
      markCurrentEntry(reasonToStatus(reason));
      scheduleAdvance(autoAdvanceMs());
    }
    fireSessionChanged();
  }

  public void close() {
    scheduler.shutdownNow();
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private void dialCurrent() {
    if (session == null) return;
    skipNonPending();
    if (session.isExhausted()) {
      endSession();
      return;
    }
    session
        .currentEntry()
        .ifPresent(
            entry -> {
              final Optional<Lead> lead = leadRepo.findById(entry.leadId());
              if (lead.isEmpty()) {
                LOG.warn("Lead {} not found — skipping", entry.leadId().value());
                session.position++;
                dialCurrent();
                return;
              }
              final Optional<OwnedNumber> local = callerIdSelector.selectFor(lead.get().phone());
              if (local.isEmpty()) {
                LOG.error("No active calling number — stopping dialer");
                stop();
                return;
              }
              onLeadChangedCb.accept(Optional.of(lead.get()));
              // New dial cycle: reset the per-call flags so the next connect is counted once.
              session.currentCallAnswered = false;
              session.currentCallConnected = false;
              session.dialedCount++;
              onStatsCb.accept(session.toStats());
              dialCommand.accept(lead.get().phone(), local.get().number());
              scheduleAdvance(noAnswerMs());
            });
  }

  /** Advance the cursor over entries already dialed/skipped in a prior session. */
  private void skipNonPending() {
    while (!session.isExhausted()
        && session
            .currentEntry()
            .map(e -> e.status() != CallListEntry.DialStatus.PENDING)
            .orElse(false)) {
      session.position++;
    }
  }

  private void scheduleAdvance(long delayMs) {
    cancelTimeout();
    session.pendingTimeout =
        scheduler.schedule(
            () -> {
              synchronized (PowerDialerService.this) {
                if (session == null) return;
                session.position++;
                if (session.isExhausted()) endSession();
                else {
                  fireSessionChanged();
                  dialCurrent();
                }
              }
            },
            delayMs,
            TimeUnit.MILLISECONDS);
  }

  private void cancelTimeout() {
    if (session != null && session.pendingTimeout != null) {
      session.pendingTimeout.cancel(false);
      session.pendingTimeout = null;
    }
  }

  private void endSession() {
    if (session == null) return;
    LOG.info(
        "Power dialer complete: {} dialed, {} connected",
        session.dialedCount,
        session.connectedCount);
    session.state = new PowerDialerState.Stopped();
    fireSessionChanged();
    onLeadChangedCb.accept(Optional.empty());
    session = null;
  }

  private void markCurrentEntry(CallListEntry.DialStatus status) {
    if (session == null || session.synthetic) return; // synthetic pool has no DB rows to update
    session.currentEntry().ifPresent(e -> callListRepo.updateEntryStatus(e.entryId(), status));
  }

  /** Build an in-memory all-leads list (entry id 0 — synthetic, never written back). */
  private static CallList syntheticAllLeads(List<Lead> leads) {
    final List<CallListEntry> entries = new ArrayList<>(leads.size());
    for (int i = 0; i < leads.size(); i++) {
      entries.add(new CallListEntry(0L, leads.get(i).id(), i, CallListEntry.DialStatus.PENDING));
    }
    final Instant now = Instant.now();
    return new CallList(ALL_LEADS_ID, "All Leads", Optional.empty(), entries, now, now);
  }

  private static CallListEntry.DialStatus reasonToStatus(String reason) {
    return "skipped".equals(reason)
        ? CallListEntry.DialStatus.SKIPPED
        : CallListEntry.DialStatus.DIALED;
  }

  /** No-answer ring timeout in ms, read live from settings (default 30s). */
  private long noAnswerMs() {
    return settings.getNoAnswerTimeoutSec() * 1_000L;
  }

  /** Delay before auto-advancing past an unanswered call, in ms (default 1s). */
  private long autoAdvanceMs() {
    return settings.getAutoAdvanceDelaySec() * 1_000L;
  }

  private void fireSessionChanged() {
    onSessionChangedCb.accept(Optional.ofNullable(session).map(Session::toRecord));
  }
}
