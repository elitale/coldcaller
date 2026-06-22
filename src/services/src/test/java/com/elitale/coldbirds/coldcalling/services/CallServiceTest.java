package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.telephony.TelephonyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CallServiceTest {

    @Mock TelephonyService       telephony;
    @Mock CallRepository         callRepo;
    @Mock ContactRepository      contactRepo;
    @Mock PhoneNumberRepository  phoneNumberRepo;

    CallService callService;

    private static final PhoneNumber LOCAL  = new PhoneNumber("+12025551001");
    private static final PhoneNumber REMOTE = new PhoneNumber("+12025551002");
    private static final PhoneNumberId LOCAL_ID = new PhoneNumberId(1L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        callService = new CallService(telephony, callRepo, contactRepo, phoneNumberRepo);
    }

    // ── dial ──────────────────────────────────────────────────────────────────

    @Test
    void dial_delegatesToTelephony() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-call-1");
        stubOwnedNumber(LOCAL, LOCAL_ID);
        stubSaveCall();

        callService.dial(REMOTE, LOCAL);

        verify(telephony).dial(LOCAL, REMOTE);
    }

    @Test
    void dial_dnc_doesNotDelegate() {
        stubContactDnc(REMOTE, true);

        callService.dial(REMOTE, LOCAL);

        verify(telephony, never()).dial(any(), any());
    }

    @Test
    void dial_unknownLocalNumber_doesNotDelegate() {
        when(phoneNumberRepo.findByNumber(LOCAL)).thenReturn(Optional.empty());

        callService.dial(REMOTE, LOCAL);

        verify(telephony, never()).dial(any(), any());
    }

    // ── onIncomingCall ────────────────────────────────────────────────────────

    @Test
    void onIncomingCall_firesCallback() {
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<PhoneNumber> callerRef = new AtomicReference<>();
        callService.setOnIncomingCall((id, caller, called) -> {
            fired.set(true);
            callerRef.set(caller);
        });

        callService.onIncomingCall("call-1", REMOTE, LOCAL);

        assertThat(fired.get()).isTrue();
        assertThat(callerRef.get()).isEqualTo(REMOTE);
    }

    // ── onCallAnswered ────────────────────────────────────────────────────────

    @Test
    void onCallAnswered_firesCallback() {
        AtomicBoolean fired = new AtomicBoolean(false);
        callService.setOnCallAnswered(id -> fired.set(true));

        callService.onCallAnswered("call-1");

        assertThat(fired.get()).isTrue();
    }

    // ── onCallEnded ───────────────────────────────────────────────────────────

    @Test
    void onCallEnded_firesCallback() {
        AtomicReference<String> reasonRef = new AtomicReference<>();
        callService.setOnCallEnded((id, reason) -> reasonRef.set(reason));

        callService.onCallEnded("call-1", "bye");

        assertThat(reasonRef.get()).isEqualTo("bye");
    }

    // ── answer ────────────────────────────────────────────────────────────────

    @Test
    void answer_delegatesToTelephony() {
        callService.answer("call-1");
        verify(telephony).answer("call-1");
    }

    // ── hangUp ────────────────────────────────────────────────────────────────

    @Test
    void hangUp_delegatesToTelephony() {
        callService.hangUp();
        verify(telephony).hangUp();
    }

    // ── updateDisposition / updateNotes ─────────────────────────────────────────

    @Test
    void updateDisposition_persistsNewDispositionAndBumpsUpdatedAt() {
        final Instant oldUpdatedAt = Instant.parse("2020-01-01T00:00:00Z");
        final Call existing = aCall(Optional.of(new CallDisposition.NoAnswer()), Optional.empty(), oldUpdatedAt);
        when(callRepo.findById(new CallId(7L))).thenReturn(Optional.of(existing));
        when(callRepo.update(any())).thenAnswer(inv -> Result.ok(inv.getArgument(0)));

        final Result<Call> result = callService.updateDisposition(new CallId(7L), new CallDisposition.Interested());

        final ArgumentCaptor<Call> saved = ArgumentCaptor.forClass(Call.class);
        verify(callRepo).update(saved.capture());
        assertThat(saved.getValue().disposition()).contains(new CallDisposition.Interested());
        assertThat(saved.getValue().updatedAt()).isAfter(oldUpdatedAt);
        assertThat(result.isOk()).isTrue();
    }

    @Test
    void updateDisposition_callNotFound_returnsErr() {
        when(callRepo.findById(new CallId(9L))).thenReturn(Optional.empty());

        final Result<Call> result = callService.updateDisposition(new CallId(9L), new CallDisposition.Busy());

        assertThat(result.isErr()).isTrue();
        verify(callRepo, never()).update(any());
    }

    @Test
    void updateNotes_persistsNotes() {
        final Call existing = aCall(Optional.empty(), Optional.empty(), Instant.parse("2020-01-01T00:00:00Z"));
        when(callRepo.findById(new CallId(3L))).thenReturn(Optional.of(existing));
        when(callRepo.update(any())).thenAnswer(inv -> Result.ok(inv.getArgument(0)));

        callService.updateNotes(new CallId(3L), "  Left a voicemail  ");

        final ArgumentCaptor<Call> saved = ArgumentCaptor.forClass(Call.class);
        verify(callRepo).update(saved.capture());
        assertThat(saved.getValue().notes()).contains("Left a voicemail");
    }

    @Test
    void updateNotes_blank_clearsNotes() {
        final Call existing = aCall(Optional.empty(), Optional.of("old"), Instant.parse("2020-01-01T00:00:00Z"));
        when(callRepo.findById(new CallId(3L))).thenReturn(Optional.of(existing));
        when(callRepo.update(any())).thenAnswer(inv -> Result.ok(inv.getArgument(0)));

        callService.updateNotes(new CallId(3L), "   ");

        final ArgumentCaptor<Call> saved = ArgumentCaptor.forClass(Call.class);
        verify(callRepo).update(saved.capture());
        assertThat(saved.getValue().notes()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Call aCall(Optional<CallDisposition> disposition, Optional<String> notes, Instant updatedAt) {
        final Instant started = Instant.parse("2020-01-01T00:00:00Z");
        return new Call(
                new CallId(7L), CallDirection.OUTBOUND, LOCAL_ID, Optional.empty(), REMOTE,
                disposition, started, Optional.of(started), Optional.of(started),
                Optional.of(0L), Optional.empty(), notes, started, updatedAt);
    }

    private void stubContactDnc(PhoneNumber number, boolean isDnc) {
        var contact = new com.elitale.coldbirds.coldcalling.domain.model.Contact(
                new ContactId(1L),
                Optional.of("Test"), Optional.of("User"),
                number,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(),
                isDnc,
                Instant.now(), Instant.now()
        );
        when(contactRepo.findByPhone(number)).thenReturn(Optional.of(contact));
    }

    private void stubOwnedNumber(PhoneNumber number, PhoneNumberId id) {
        var owned = new com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber(
                id, number, Optional.empty(),
                new AreaCode("202"), "twilio", new NumberReputation.Clean(),
                0, true, Instant.now(), Instant.now()
        );
        when(phoneNumberRepo.findByNumber(number)).thenReturn(Optional.of(owned));
    }

    private void stubSaveCall() {
        when(callRepo.save(any())).thenReturn(
                Result.err("stub — not used in this test path")
        );
    }
}
