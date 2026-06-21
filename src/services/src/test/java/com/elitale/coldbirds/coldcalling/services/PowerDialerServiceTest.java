package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.*;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
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
    @Mock ContactRepository        contactRepo;
    @Mock PhoneNumberService       phoneNumberService;
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
        // Non-immediate by default: captures tasks but does NOT run them.
        // This prevents the 30-second NO_ANSWER timer from draining the list during start().
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service = new PowerDialerService(
                callListRepo, contactRepo, phoneNumberService, dialCaptor, scheduler);
        stubOwnedNumber();
    }

    // ── start ─────────────────────────────────────────────────────────────────

    @Test
    void start_dialsFirstContact() {
        stubTwoContactList();
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
        stubTwoContactList();
        service.start(LIST_ID);
        // Session is still Running — NO_ANSWER timer was captured, not run
        assertThat(service.start(LIST_ID)).isInstanceOf(Result.Err.class);
    }

    // ── auto-advance ──────────────────────────────────────────────────────────

    @Test
    void notifyCallEnded_unanswered_autoAdvancesToNextContact() {
        stubTwoContactList();
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
        stubTwoContactList();
        service.start(LIST_ID);
        service.notifyCallAnswered("c1");
        assertThat(service.getStats()).map(PowerDialerService.SessionStats::connectedCount).hasValue(1);
    }

    @Test
    void notifyCallEnded_afterAnswered_doesNotAutoAdvance() {
        stubTwoContactList();
        service.start(LIST_ID);
        dialed.clear();
        service.notifyCallAnswered("c1");
        service.notifyCallEnded("c1", "bye");
        assertThat(dialed).isEmpty();
    }

    // ── manual advance ────────────────────────────────────────────────────────

    @Test
    void advance_dialsNextContact() {
        stubTwoContactList();
        service.start(LIST_ID);
        service.notifyCallAnswered("c1");
        service.notifyCallEnded("c1", "bye");
        dialed.clear();
        service.advance();
        assertThat(dialed).hasSize(1);
        assertThat(dialed.get(0)[0]).isEqualTo(PHONE_B);
    }

    // ── stop / pause ──────────────────────────────────────────────────────────

    @Test
    void stop_clearsSession() {
        stubTwoContactList();
        service.start(LIST_ID);
        service.stop();
        assertThat(service.getCurrentSession()).isEmpty();
    }

    @Test
    void listExhausted_sessionEnds() {
        stubSingleContactList();
        service.start(LIST_ID);
        // Run the NO_ANSWER task; position advances past end → endSession()
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(taskCaptor.capture(), anyLong(), any());
        taskCaptor.getValue().run();
        assertThat(service.getCurrentSession()).isEmpty();
    }

    @Test
    void pause_preventsAutoAdvance() {
        stubTwoContactList();
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
                new AreaCode("202"), "telnyx", new NumberReputation.Clean(),
                0, true, Instant.now(), Instant.now());
        when(phoneNumberService.listOwned()).thenReturn(List.of(on));
    }

    private void stubTwoContactList() {
        ContactId idA = new ContactId(10L), idB = new ContactId(20L);
        CallList list = new CallList(LIST_ID, "Test", Optional.empty(), List.of(
                new CallListEntry(1L, idA, 0, CallListEntry.DialStatus.PENDING),
                new CallListEntry(2L, idB, 1, CallListEntry.DialStatus.PENDING)),
                Instant.now(), Instant.now());
        when(callListRepo.findById(LIST_ID)).thenReturn(Optional.of(list));
        when(contactRepo.findById(idA)).thenReturn(Optional.of(makeContact(idA, PHONE_A)));
        when(contactRepo.findById(idB)).thenReturn(Optional.of(makeContact(idB, PHONE_B)));
    }

    private void stubSingleContactList() {
        ContactId idA = new ContactId(10L);
        CallList list = new CallList(LIST_ID, "Test", Optional.empty(), List.of(
                new CallListEntry(1L, idA, 0, CallListEntry.DialStatus.PENDING)),
                Instant.now(), Instant.now());
        when(callListRepo.findById(LIST_ID)).thenReturn(Optional.of(list));
        when(contactRepo.findById(idA)).thenReturn(Optional.of(makeContact(idA, PHONE_A)));
    }

    private static Contact makeContact(ContactId id, PhoneNumber phone) {
        return new Contact(id, Optional.of("Test"), Optional.empty(), phone,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), false, Instant.now(), Instant.now());
    }
}
