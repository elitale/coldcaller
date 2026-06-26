package com.elitale.coldbirds.coldcalling.services;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.CallListEntry;
import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.PowerDialerSession;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;

/**
 * Property / fuzz tests guarding the power-dialer counter and state invariants against ANY
 * ordering of call events.
 *
 * <p>{@link PowerDialerSession} enforces {@code 0 <= connectedCount <= dialedCount} and is
 * rebuilt on every UI tick, so a single bad event sequence throws on the FX thread and crashes
 * the app. Hand-written examples only cover the orderings we thought of; these tests throw
 * thousands of <em>randomised</em> sequences (answered, ended, advance, pause, resume, timer
 * fired, stop, restart — including duplicates and out-of-order events) at the service and assert
 * the invariant after every single step. A failure prints the exact reproducing sequence.
 */
class PowerDialerServiceInvariantTest {

    private static final CallListId LIST_ID = new CallListId(1L);
    private static final PhoneNumber LOCAL = new PhoneNumber("+12025551000");
    private static final Instant NOW = Instant.now();

    private static final int SEQUENCES = 400;
    private static final int STEPS_PER_SEQUENCE = 50;

    @Test
    void connectedNeverExceedsDialed_underRandomEventSequences() {
        for (long seed = 0; seed < SEQUENCES; seed++) {
            runSequence(seed);
        }
    }

    private void runSequence(long seed) {
        final Random random = new Random(seed);
        final TestScheduler scheduler = new TestScheduler();
        final PowerDialerService service = freshService(scheduler.asExecutor(), 6);

        final List<String> log = new ArrayList<>();
        step(service, seed, log, "start", () -> service.start(LIST_ID));

        for (int i = 0; i < STEPS_PER_SEQUENCE; i++) {
            switch (random.nextInt(9)) {
                case 0, 1 -> step(service, seed, log, "answered", () -> service.notifyCallAnswered("c"));
                case 2    -> {
                    final String reason = reason(random);
                    step(service, seed, log, "ended:" + reason, () -> service.notifyCallEnded("c", reason));
                }
                case 3    -> step(service, seed, log, "advance", service::advance);
                case 4    -> step(service, seed, log, "pause", service::pause);
                case 5    -> step(service, seed, log, "resume", service::resume);
                case 6, 7 -> step(service, seed, log, "timer", () -> scheduler.fireLatest());
                default   -> {
                    if (service.getCurrentSession().isEmpty()) {
                        step(service, seed, log, "restart", () -> service.start(LIST_ID));
                    } else {
                        step(service, seed, log, "stop", service::stop);
                    }
                }
            }
        }
    }

    /**
     * Runs one event, then asserts the invariant. The op label is appended <em>before</em> the
     * event runs, so a failure here — whether thrown by the mutating call itself (the session
     * record is rebuilt inside {@code fireSessionChanged}) or by the follow-up read — names the
     * exact sequence that reproduces it.
     */
    private static void step(final PowerDialerService service, final long seed,
                             final List<String> log, final String label, final Runnable action) {
        log.add(label);
        assertThatCode(action::run)
                .as("operation must not break the session invariant; seed=" + seed + " sequence=" + log)
                .doesNotThrowAnyException();
        assertInvariant(service, seed, log);
    }

    private static String reason(final Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "bye";
            case 1 -> "no-answer";
            case 2 -> "busy";
            default -> "skipped";
        };
    }

    /** The one property that matters: no event ordering may break the session record. */
    private static void assertInvariant(final PowerDialerService service, final long seed,
                                        final List<String> log) {
        final String context = "seed=" + seed + " sequence=" + log;

        assertThatCode(service::getCurrentSession)
                .as("getCurrentSession must never throw; " + context)
                .doesNotThrowAnyException();
        assertThatCode(service::getStats)
                .as("getStats must never throw; " + context)
                .doesNotThrowAnyException();

        service.getCurrentSession().ifPresent(s -> {
            assertThat(s.dialedCount()).as("dialedCount >= 0; " + context).isGreaterThanOrEqualTo(0);
            assertThat(s.connectedCount()).as("connectedCount >= 0; " + context).isGreaterThanOrEqualTo(0);
            assertThat(s.connectedCount())
                    .as("connectedCount must not exceed dialedCount; " + context)
                    .isLessThanOrEqualTo(s.dialedCount());
            assertThat(s.currentPosition()).as("currentPosition >= 0; " + context).isGreaterThanOrEqualTo(0);
        });

        service.getStats().ifPresent(stats ->
                assertThat(stats.connectedCount())
                        .as("stats connectedCount must not exceed dialedCount; " + context)
                        .isLessThanOrEqualTo(stats.dialedCount()));
    }

    // ── fixture ───────────────────────────────────────────────────────────────────

    private static PowerDialerService freshService(final ScheduledExecutorService scheduler,
                                                   final int leadCount) {
        final CallListRepository callListRepo = mock(CallListRepository.class);
        final LeadRepository leadRepo = mock(LeadRepository.class);
        final CallerIdSelector callerIdSelector = mock(CallerIdSelector.class);
        final SettingsService settings = mock(SettingsService.class);

        when(settings.getNoAnswerTimeoutSec()).thenReturn(30);
        when(settings.getAutoAdvanceDelaySec()).thenReturn(1);

        final List<CallListEntry> entries = new ArrayList<>();
        for (int i = 0; i < leadCount; i++) {
            final LeadId id = new LeadId(10L * (i + 1));
            entries.add(new CallListEntry(i + 1L, id, i, CallListEntry.DialStatus.PENDING));
            when(leadRepo.findById(id)).thenReturn(Optional.of(makeLead(id, phone(i))));
        }
        final CallList list = new CallList(LIST_ID, "Fuzz", Optional.empty(),
                List.copyOf(entries), NOW, NOW);
        when(callListRepo.findById(LIST_ID)).thenReturn(Optional.of(list));

        final OwnedNumber owned = new OwnedNumber(new PhoneNumberId(99L), LOCAL, Optional.empty(),
                new AreaCode("202"), "twilio", new NumberReputation.Clean(),
                0, true, NOW, NOW);
        when(callerIdSelector.selectFor(any())).thenReturn(Optional.of(owned));

        return new PowerDialerService(callListRepo, leadRepo, callerIdSelector, settings,
                (remote, local) -> { }, scheduler);
    }

    private static PhoneNumber phone(final int index) {
        return new PhoneNumber(String.format("+1202555%04d", 1000 + index));
    }

    private static Lead makeLead(final LeadId id, final PhoneNumber phone) {
        return new Lead(id, Optional.of("Test"), Optional.empty(), phone,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), false, Map.of(), LeadStatus.NEW,
                NOW, NOW);
    }

    // ── deterministic scheduler ─────────────────────────────────────────────────────

    /**
     * Stands in for the real {@link ScheduledExecutorService}: captures scheduled tasks and lets
     * the test fire the most recent still-live one, simulating a no-answer / auto-advance timer
     * elapsing. Honours cancellation so a replaced timer never fires.
     */
    private static final class TestScheduler {

        private final Deque<Task> tasks = new ArrayDeque<>();
        private final ScheduledExecutorService executor;

        TestScheduler() {
            executor = mock(ScheduledExecutorService.class);
            when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> {
                        final Task task = new Task(invocation.getArgument(0));
                        tasks.addLast(task);
                        return new CancellableFuture(task);
                    });
        }

        ScheduledExecutorService asExecutor() {
            return executor;
        }

        /** Run the most-recently scheduled, not-cancelled task. Returns true if one ran. */
        boolean fireLatest() {
            while (!tasks.isEmpty()) {
                final Task task = tasks.pollLast();
                if (!task.cancelled) {
                    task.runnable.run();
                    return true;
                }
            }
            return false;
        }

        private static final class Task {
            private final Runnable runnable;
            private volatile boolean cancelled;

            Task(final Runnable runnable) {
                this.runnable = runnable;
            }
        }

        private static final class CancellableFuture implements ScheduledFuture<Object> {
            private final Task task;

            CancellableFuture(final Task task) {
                this.task = task;
            }

            @Override public long getDelay(final TimeUnit unit) { return 0L; }
            @Override public int compareTo(final Delayed other) { return 0; }
            @Override public boolean cancel(final boolean mayInterruptIfRunning) {
                task.cancelled = true;
                return true;
            }
            @Override public boolean isCancelled() { return task.cancelled; }
            @Override public boolean isDone() { return task.cancelled; }
            @Override public Object get() { return null; }
            @Override public Object get(final long timeout, final TimeUnit unit) { return null; }
        }
    }
}
