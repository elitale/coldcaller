package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.*;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository.NewCallList;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages power dialer sessions: iterates a {@link CallList}, dials each entry,
 * and auto-advances on unanswered calls.
 *
 * <p>Threading: all public control/query methods are {@code synchronized}.
 * Scheduler callbacks re-enter via {@code synchronized (this)}.
 * UI callbacks fire while the lock is held — they must not re-enter this service.
 */
public final class PowerDialerService {

    private static final Logger LOG = LoggerFactory.getLogger(PowerDialerService.class);

    /** Snapshot statistics for UI display. */
    public record SessionStats(int dialedCount, int connectedCount, int remaining) {}

    // ── Session state (only accessed while lock held) ─────────────────────────

    private static final class Session {
        final CallList         callList;
        int                    position;
        int                    dialedCount;
        int                    connectedCount;
        boolean                currentCallAnswered;
        ScheduledFuture<?>     pendingTimeout;
        PowerDialerState       state = new PowerDialerState.Running();
        final Instant          startedAt = Instant.now();

        Session(CallList callList) { this.callList = callList; }

        boolean isExhausted() { return position >= callList.entries().size(); }

        Optional<CallListEntry> currentEntry() {
            return position < callList.entries().size()
                    ? Optional.of(callList.entries().get(position)) : Optional.empty();
        }

        PowerDialerSession toRecord() {
            return new PowerDialerSession(0L, callList.id(), position, state,
                    dialedCount, connectedCount, startedAt, Optional.empty());
        }

        SessionStats toStats() {
            return new SessionStats(dialedCount, connectedCount,
                    Math.max(0, callList.entries().size() - position));
        }
    }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final CallListRepository                  callListRepo;
    private final ContactRepository                   contactRepo;
    private final PhoneNumberService                  phoneNumberService;
    private final SettingsService                     settings;
    private final BiConsumer<PhoneNumber, PhoneNumber> dialCommand;
    private final ScheduledExecutorService            scheduler;

    // ── Mutable state (all guarded by this) ───────────────────────────────────

    private Session session;
    private Consumer<Optional<PowerDialerSession>> onSessionChangedCb = s -> {};
    private Consumer<Optional<Contact>>            onContactChangedCb = c -> {};
    private Consumer<SessionStats>                 onStatsCb          = s -> {};

    // ── Constructors ──────────────────────────────────────────────────────────

    public PowerDialerService(
            CallListRepository callListRepo,
            ContactRepository contactRepo,
            PhoneNumberService phoneNumberService,
            SettingsService settings,
            BiConsumer<PhoneNumber, PhoneNumber> dialCommand) {
        this(callListRepo, contactRepo, phoneNumberService, settings, dialCommand,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "power-dialer-scheduler");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** Package-private: for tests — supply a controllable scheduler. */
    PowerDialerService(
            CallListRepository callListRepo,
            ContactRepository contactRepo,
            PhoneNumberService phoneNumberService,
            SettingsService settings,
            BiConsumer<PhoneNumber, PhoneNumber> dialCommand,
            ScheduledExecutorService scheduler) {
        this.callListRepo       = Objects.requireNonNull(callListRepo);
        this.contactRepo        = Objects.requireNonNull(contactRepo);
        this.phoneNumberService = Objects.requireNonNull(phoneNumberService);
        this.settings           = Objects.requireNonNull(settings);
        this.dialCommand        = Objects.requireNonNull(dialCommand);
        this.scheduler          = Objects.requireNonNull(scheduler);
    }

    // ── Callback registration ─────────────────────────────────────────────────

    public void setOnSessionChanged(Consumer<Optional<PowerDialerSession>> cb) {
        this.onSessionChangedCb = Objects.requireNonNull(cb);
    }

    public void setOnContactChanged(Consumer<Optional<Contact>> cb) {
        this.onContactChangedCb = Objects.requireNonNull(cb);
    }

    public void setOnStatsChanged(Consumer<SessionStats> cb) {
        this.onStatsCb = Objects.requireNonNull(cb);
    }

    // ── Control API ───────────────────────────────────────────────────────────

    public synchronized Result<Void> start(CallListId listId) {
        if (session != null && session.state instanceof PowerDialerState.Running)
            return Result.err("A session is already running");
        return callListRepo.findById(Objects.requireNonNull(listId))
                .filter(l -> !l.entries().isEmpty())
                .<Result<Void>>map(l -> {
                    session = new Session(l);
                    fireSessionChanged();
                    dialCurrent();
                    return Result.ok(null);
                })
                .orElse(Result.err("Call list not found or empty: " + listId.value()));
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
        onContactChangedCb.accept(Optional.empty());
        session = null;
    }

    /** Manually advance to the next contact after an answered call ends. */
    public synchronized void advance() {
        if (session == null || !(session.state instanceof PowerDialerState.Running)) return;
        cancelTimeout();
        session.position++;
        session.currentCallAnswered = false;
        if (session.isExhausted()) endSession();
        else { fireSessionChanged(); dialCurrent(); }
    }

    // ── Query API ─────────────────────────────────────────────────────────────

    public List<CallList> getCallLists() { return callListRepo.findAll(); }

    public Result<CallList> createCallList(String name) {
        if (name == null || name.isBlank()) return Result.err("Name must not be blank");
        return callListRepo.save(new NewCallList(name.trim(), Optional.empty()));
    }

    public synchronized Optional<PowerDialerSession> getCurrentSession() {
        return Optional.ofNullable(session).map(Session::toRecord);
    }

    public synchronized Optional<Contact> getCurrentContact() {
        if (session == null) return Optional.empty();
        return session.currentEntry().flatMap(e -> contactRepo.findById(e.contactId()));
    }

    public synchronized Optional<SessionStats> getStats() {
        return Optional.ofNullable(session).map(Session::toStats);
    }

    /**
     * The next {@code n} contacts queued after the current one, in dial order, for the
     * queue-preview panel. Returns an empty list when no session is active, {@code n <= 0},
     * or the list is exhausted; clamps to however many remain.
     *
     * @param n maximum number of upcoming contacts to return
     */
    public synchronized List<Contact> upcoming(int n) {
        if (session == null || n <= 0) return List.of();
        final List<CallListEntry> entries = session.callList.entries();
        final List<Contact> result = new ArrayList<>();
        for (int i = session.position + 1; i < entries.size() && result.size() < n; i++) {
            contactRepo.findById(entries.get(i).contactId()).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    // ── Call event notifications (composed in ColdCallingApp) ─────────────────

    public synchronized void notifyCallAnswered(String callId) {
        if (session == null) return;
        cancelTimeout();
        session.currentCallAnswered = true;
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

    public void close() { scheduler.shutdownNow(); }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void dialCurrent() {
        if (session == null || session.isExhausted()) return;
        session.currentEntry().ifPresent(entry -> {
            final Optional<Contact> contact = contactRepo.findById(entry.contactId());
            if (contact.isEmpty()) {
                LOG.warn("Contact {} not found — skipping", entry.contactId().value());
                session.position++;
                if (session.isExhausted()) { endSession(); return; }
                dialCurrent();
                return;
            }
            final Optional<OwnedNumber> local = resolveLocal();
            if (local.isEmpty()) { LOG.error("No owned number — stopping dialer"); stop(); return; }
            onContactChangedCb.accept(Optional.of(contact.get()));
            session.dialedCount++;
            onStatsCb.accept(session.toStats());
            dialCommand.accept(contact.get().phone(), local.get().number());
            scheduleAdvance(noAnswerMs());
        });
    }

    private void scheduleAdvance(long delayMs) {
        cancelTimeout();
        session.pendingTimeout = scheduler.schedule(() -> {
            synchronized (PowerDialerService.this) {
                if (session == null) return;
                session.position++;
                if (session.isExhausted()) endSession();
                else { fireSessionChanged(); dialCurrent(); }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTimeout() {
        if (session != null && session.pendingTimeout != null) {
            session.pendingTimeout.cancel(false);
            session.pendingTimeout = null;
        }
    }

    private void endSession() {
        if (session == null) return;
        LOG.info("Power dialer complete: {} dialed, {} connected",
                session.dialedCount, session.connectedCount);
        session.state = new PowerDialerState.Stopped();
        fireSessionChanged();
        onContactChangedCb.accept(Optional.empty());
        session = null;
    }

    private void markCurrentEntry(CallListEntry.DialStatus status) {
        if (session != null)
            session.currentEntry().ifPresent(e -> callListRepo.updateEntryStatus(e.entryId(), status));
    }

    private Optional<OwnedNumber> resolveLocal() {
        final List<OwnedNumber> owned = phoneNumberService.listOwned();
        if (owned.isEmpty()) return phoneNumberService.getDefault();
        return Optional.of(owned.get(session.dialedCount % owned.size()));
    }

    private static CallListEntry.DialStatus reasonToStatus(String reason) {
        return "skipped".equals(reason) ? CallListEntry.DialStatus.SKIPPED : CallListEntry.DialStatus.DIALED;
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
