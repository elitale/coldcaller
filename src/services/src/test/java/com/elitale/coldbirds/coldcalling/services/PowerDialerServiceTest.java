package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.*;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PowerDialerServiceTest {

    @Mock CallListRepository       callListRepo;
    @Mock LeadRepository           leadRepo;
    @Mock CallerIdSelector         callerIdSelector;
    @Mock SettingsService          settings;
    @Mock ScheduledExecutorService scheduler;

    final List<PhoneNumber[]> dialed = new ArrayList<>();
    final BiConsumer<PhoneNumber, PhoneNumber> dialCaptor = (r, l) -> dialed.add(new PhoneNumber[]{r, l});

    PowerDialerService service;

    static final CallListId  LIST_ID  = new CallListId(1L);
    static final PhoneNumber PHONE_A  = new PhoneNumber("+12025550001");
    static final PhoneNumber PHONE_B  = new PhoneNumber("+12025550002");
    static final PhoneNumber LOCAL    = new PhoneNumber("+12025551000");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Default timeouts mirror SettingsService defaults (30s no-answer, 1s auto-advance).
        when(settings.getNoAnswerTimeoutSec()).thenReturn(30);
        when(settings.getAutoAdvanceDelaySec()).thenReturn(1);
        // Non-immediate by default: captures tasks but does NOT run them.
        // This prevents the 30-second NO_ANSWER timer from draining the list during start().
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service = new PowerDialerService(
                callListRepo, leadRepo, callerIdSelector, settings, dialCaptor, scheduler);
        stubOwnedNumber();
    }

    // ── start ─────────────────────────────────────────────────────────────────

    @Test
    void start_dialsFirstLead() {
        stubTwoLeadList();
        assertThat(service.start(LIST_ID)).isInstanceOf(Result.Ok.class);
        assertThat(dialed).hasSize(1);
        assertThat(dialed.get(0)[0]).isEqualTo(PHONE_A);
    }

    @Test
    void start_emptyList_returnsError() {
        when(callListRepo.findById(LIST_ID)).thenReturn(Optional.of(
                new CallList(LIST_ID, "Empty", Optional.empty(), List.of(), Instant.now(), Instant.now())));
        assertThat(service.start(LIST_ID)).isInstanceOf(Result.Err.class);
    }

    @Test
    void start_alreadyRunning_returnsError() {
        stubTwoLeadList();
        service.start(LIST_ID);
        // Session is still Running — NO_ANSWER timer was captured, not run
        assertThat(service.start(LIST_ID)).isInstanceOf(Result.Err.class);
    }

    // ── auto-advance ──────────────────────────────────────────────────────────

    @Test
    void notifyCallEnded_unanswered_autoAdvancesToNextLead() {
        stubTwoLeadList();
        service.start(LIST_ID);
        dialed.clear();
        // notifyCallEnded schedules an AUTO_ADVANCE_MS (1 000 ms) task; capture and run it.
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        service.notifyCallEnded("c1", "no-answer");
        verify(scheduler, atLeast(1)).schedule(taskCaptor.capture(), eq(1_000L), any());
        taskCaptor.getValue().run();
        assertThat(dialed).hasSize(1);
        assertThat(dialed.get(0)[0]).isEqualTo(PHONE_B);
    }

    @Test
    void notifyCallAnswered_incrementsConnectedCount() {
        stubTwoLeadList();
        service.start(LIST_ID);
        service.notifyCallAnswered("c1");
        assertThat(service.getStats()).map(PowerDialerService.SessionStats::connectedCount).hasValue(1);
    }

    @Test
    void notifyCallEnded_afterAnswered_doesNotAutoAdvance() {
        stubTwoLeadList();
        service.start(LIST_ID);
        dialed.clear();
        service.notifyCallAnswered("c1");
        service.notifyCallEnded("c1", "bye");
        assertThat(dialed).isEmpty();
    }

    // ── manual advance ────────────────────────────────────────────────────────

    @Test
    void advance_dialsNextLead() {
        stubTwoLeadList();
        service.start(LIST_ID);
        service.notifyCallAnswered("c1");
        service.notifyCallEnded("c1", "bye");
        dialed.clear();
        service.advance();
        assertThat(dialed).hasSize(1);
        assertThat(dialed.get(0)[0]).isEqualTo(PHONE_B);
    }
    // ── queue preview ────────────────────────────────────────────

    @Test
    void upcoming_returnsNextLeadsAfterCurrent() {
        stubTwoLeadList();
        service.start(LIST_ID);
        // Current position is PHONE_A (index 0); upcoming is PHONE_B (index 1).
        assertThat(service.upcoming(5))
                .extracting(c -> c.phone())
                .containsExactly(PHONE_B);
    }

    @Test
    void upcoming_clampsToRequestedCount() {
        stubTwoLeadList();
        service.start(LIST_ID);
        assertThat(service.upcoming(0)).isEmpty();
    }

    @Test
    void upcoming_emptyWhenNoSession() {
        assertThat(service.upcoming(3)).isEmpty();
    }

    // ── settings-driven timeouts ─────────────────────────────────────

    @Test
    void dialCurrent_usesNoAnswerTimeoutFromSettings() {
        when(settings.getNoAnswerTimeoutSec()).thenReturn(12);
        stubTwoLeadList();
        service.start(LIST_ID);
        // start() dials the first lead and schedules the no-answer timer at 12_000 ms.
        verify(scheduler).schedule(any(Runnable.class), eq(12_000L), any());
    }

    @Test
    void notifyCallEnded_usesAutoAdvanceDelayFromSettings() {
        when(settings.getAutoAdvanceDelaySec()).thenReturn(3);
        stubTwoLeadList();
        service.start(LIST_ID);
        service.notifyCallEnded("c1", "no-answer");
        verify(scheduler).schedule(any(Runnable.class), eq(3_000L), any());
    }
    // ── stop / pause ──────────────────────────────────────────────────────────

    @Test
    void stop_clearsSession() {
        stubTwoLeadList();
        service.start(LIST_ID);
        service.stop();
        assertThat(service.getCurrentSession()).isEmpty();
    }

    @Test
    void listExhausted_sessionEnds() {
        stubSingleLeadList();
        service.start(LIST_ID);
        // Run the NO_ANSWER task; position advances past end → endSession()
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(taskCaptor.capture(), anyLong(), any());
        taskCaptor.getValue().run();
        assertThat(service.getCurrentSession()).isEmpty();
    }

    @Test
    void pause_preventsAutoAdvance() {
        stubTwoLeadList();
        service.start(LIST_ID);
        dialed.clear();
        service.pause();
        // Paused guard in notifyCallEnded returns early → no advance, no new dial
        service.notifyCallEnded("c1", "no-answer");
        assertThat(dialed).isEmpty();
    }

    // ── createCallList ────────────────────────────────────────────────────────

    @Test
    void createCallList_blankName_returnsError() {
        assertThat(service.createCallList("  ")).isInstanceOf(Result.Err.class);
    }

    @Test
    void createCallList_delegatesToRepo() {
        CallList expected = new CallList(LIST_ID, "Sales", Optional.empty(),
                List.of(), Instant.now(), Instant.now());
        when(callListRepo.save(any())).thenReturn(Result.ok(expected));
        Result<CallList> r = service.createCallList("Sales");
        assertThat(r).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<CallList>) r).value().name()).isEqualTo("Sales");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubOwnedNumber() {
        OwnedNumber on = new OwnedNumber(new PhoneNumberId(99L), LOCAL, Optional.empty(),
                new AreaCode("202"), "twilio", new NumberReputation.Clean(),
                0, true, Instant.now(), Instant.now());
        when(callerIdSelector.selectFor(any())).thenReturn(Optional.of(on));
    }

    private void stubTwoLeadList() {
        LeadId idA = new LeadId(10L), idB = new LeadId(20L);
        CallList list = new CallList(LIST_ID, "Test", Optional.empty(), List.of(
                new CallListEntry(1L, idA, 0, CallListEntry.DialStatus.PENDING),
                new CallListEntry(2L, idB, 1, CallListEntry.DialStatus.PENDING)),
                Instant.now(), Instant.now());
        when(callListRepo.findById(LIST_ID)).thenReturn(Optional.of(list));
        when(leadRepo.findById(idA)).thenReturn(Optional.of(makeLead(idA, PHONE_A)));
        when(leadRepo.findById(idB)).thenReturn(Optional.of(makeLead(idB, PHONE_B)));
    }

    private void stubSingleLeadList() {
        LeadId idA = new LeadId(10L);
        CallList list = new CallList(LIST_ID, "Test", Optional.empty(), List.of(
                new CallListEntry(1L, idA, 0, CallListEntry.DialStatus.PENDING)),
                Instant.now(), Instant.now());
        when(callListRepo.findById(LIST_ID)).thenReturn(Optional.of(list));
        when(leadRepo.findById(idA)).thenReturn(Optional.of(makeLead(idA, PHONE_A)));
    }

    private static Lead makeLead(LeadId id, PhoneNumber phone) {
        return new Lead(id, Optional.of("Test"), Optional.empty(), phone,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), false, Instant.now(), Instant.now());
    }
}
