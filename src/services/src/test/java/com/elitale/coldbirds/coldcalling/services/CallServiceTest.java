package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.telephony.TelephonyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CallServiceTest {

    @Mock TelephonyService       telephony;
    @Mock CallRepository         callRepo;
    @Mock LeadRepository         leadRepo;
    @Mock PhoneNumberRepository  phoneNumberRepo;
    @Mock SettingsService        settings;

    CallService callService;

    private static final PhoneNumber LOCAL  = new PhoneNumber("+12025551001");
    private static final PhoneNumber REMOTE = new PhoneNumber("+12025551002");
    private static final PhoneNumberId LOCAL_ID = new PhoneNumberId(1L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        callService = new CallService(telephony, callRepo, leadRepo, phoneNumberRepo, settings);
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
        stubLeadDnc(REMOTE, true);

        callService.dial(REMOTE, LOCAL);

        verify(telephony, never()).dial(any(), any());
    }

    @Test
    void dial_unknownLocalNumber_doesNotDelegate() {
        when(phoneNumberRepo.findByNumber(LOCAL)).thenReturn(Optional.empty());

        callService.dial(REMOTE, LOCAL);

        verify(telephony, never()).dial(any(), any());
    }

    @Test
    void dial_firesRingingCallbackWithCallId() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-call-9");
        stubOwnedNumber(LOCAL, LOCAL_ID);
        AtomicReference<String> ringingId = new AtomicReference<>();
        callService.setOnCallRinging(ringingId::set);

        callService.dial(REMOTE, LOCAL);

        assertThat(ringingId.get()).isEqualTo("sip-call-9");
    }

    @Test
    void dial_firesCallStartingBeforePlacingTheCall() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-call-9");
        stubOwnedNumber(LOCAL, LOCAL_ID);

        @SuppressWarnings("unchecked")
        BiConsumer<String, String> starting = mock(BiConsumer.class);
        callService.setOnCallStarting(starting);

        callService.dial(REMOTE, LOCAL);

        // The screen-show callback must fire BEFORE the SIP INVITE is dispatched.
        InOrder order = inOrder(starting, telephony);
        order.verify(starting).accept(REMOTE.value(), LOCAL.value());
        order.verify(telephony).dial(LOCAL, REMOTE);
    }

    @Test
    void dial_callStartingThenRinging_inOrder() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-call-9");
        stubOwnedNumber(LOCAL, LOCAL_ID);

        @SuppressWarnings("unchecked")
        BiConsumer<String, String> starting = mock(BiConsumer.class);
        @SuppressWarnings("unchecked")
        Consumer<String> ringing = mock(Consumer.class);
        callService.setOnCallStarting(starting);
        callService.setOnCallRinging(ringing);

        callService.dial(REMOTE, LOCAL);

        InOrder order = inOrder(starting, ringing);
        order.verify(starting).accept(REMOTE.value(), LOCAL.value());
        order.verify(ringing).accept("sip-call-9");
    }

    @Test
    void dial_dnc_doesNotFireCallStarting() {
        stubLeadDnc(REMOTE, true);
        @SuppressWarnings("unchecked")
        BiConsumer<String, String> starting = mock(BiConsumer.class);
        callService.setOnCallStarting(starting);

        callService.dial(REMOTE, LOCAL);

        verify(starting, never()).accept(any(), any());
    }

    @Test
    void dial_unknownLocalNumber_doesNotFireCallStarting() {
        when(phoneNumberRepo.findByNumber(LOCAL)).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        BiConsumer<String, String> starting = mock(BiConsumer.class);
        callService.setOnCallStarting(starting);

        callService.dial(REMOTE, LOCAL);

        verify(starting, never()).accept(any(), any());
    }

    @Test
    void dial_marksCallDirectionOutbound() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-call-9");
        stubOwnedNumber(LOCAL, LOCAL_ID);

        callService.dial(REMOTE, LOCAL);

        assertThat(callService.getActiveCallDirection("sip-call-9"))
                .contains(CallDirection.OUTBOUND);
    }

    @Test
    void getActiveCallDirection_inboundCall() {
        callService.onIncomingCall("call-in", REMOTE, LOCAL);

        assertThat(callService.getActiveCallDirection("call-in"))
                .contains(CallDirection.INBOUND);
    }

    @Test
    void getActiveCallDirection_unknownCall_isEmpty() {
        assertThat(callService.getActiveCallDirection("nope")).isEmpty();
    }

    @Test
    void dial_blankCallId_firesFailedCallback() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("");
        stubOwnedNumber(LOCAL, LOCAL_ID);
        AtomicReference<String> failedRemote = new AtomicReference<>();
        AtomicReference<String> failedReason = new AtomicReference<>();
        callService.setOnCallFailed((remote, reason) -> {
            failedRemote.set(remote);
            failedReason.set(reason);
        });

        callService.dial(REMOTE, LOCAL);

        assertThat(failedRemote.get()).isEqualTo(REMOTE.value());
        assertThat(failedReason.get()).isNotBlank();
    }

    @Test
    void dial_dnc_firesFailedCallback() {
        stubLeadDnc(REMOTE, true);
        AtomicReference<String> failedReason = new AtomicReference<>();
        callService.setOnCallFailed((remote, reason) -> failedReason.set(reason));

        callService.dial(REMOTE, LOCAL);

        assertThat(failedReason.get()).contains("Do-Not-Call");
    }

    @Test
    void dial_unknownLocalNumber_firesFailedCallback() {
        when(phoneNumberRepo.findByNumber(LOCAL)).thenReturn(Optional.empty());
        AtomicReference<String> failedReason = new AtomicReference<>();
        callService.setOnCallFailed((remote, reason) -> failedReason.set(reason));

        callService.dial(REMOTE, LOCAL);

        assertThat(failedReason.get()).isNotBlank();
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

    // ── audio levels ────────────────────────────────────────────────────────────

    @Test
    void micLevel_delegatesToTelephony() {
        when(telephony.micLevel()).thenReturn(0.42);
        assertThat(callService.micLevel()).isEqualTo(0.42);
    }

    @Test
    void remoteLevel_delegatesToTelephony() {
        when(telephony.remoteLevel()).thenReturn(0.73);
        assertThat(callService.remoteLevel()).isEqualTo(0.73);
    }

    @Test
    void isRecording_delegatesToTelephony() {
        when(telephony.isRecording()).thenReturn(true);
        assertThat(callService.isRecording()).isTrue();
    }

    // ── dropVoicemail ───────────────────────────────────────────────────────────

    @Test
    void dropVoicemail_disabled_returnsEmpty_andSkipsTelephony() {
        when(settings.isVoicemailDropEnabled()).thenReturn(false);

        assertThat(callService.dropVoicemail()).isEmpty();
        verify(telephony, never()).playGreeting(any());
    }

    @Test
    void dropVoicemail_noGreetingConfigured_returnsEmpty_andSkipsTelephony() {
        when(settings.isVoicemailDropEnabled()).thenReturn(true);
        when(settings.getVoicemailGreetingPath()).thenReturn("   ");

        assertThat(callService.dropVoicemail()).isEmpty();
        verify(telephony, never()).playGreeting(any());
    }

    @Test
    void dropVoicemail_enabledWithGreeting_delegatesToTelephony() {
        when(settings.isVoicemailDropEnabled()).thenReturn(true);
        when(settings.getVoicemailGreetingPath()).thenReturn("/greetings/vm.wav");
        when(telephony.playGreeting(Path.of("/greetings/vm.wav")))
                .thenReturn(Optional.of(Duration.ofSeconds(3)));

        assertThat(callService.dropVoicemail()).contains(Duration.ofSeconds(3));
        verify(telephony).playGreeting(Path.of("/greetings/vm.wav"));
    }

    @Test
    void dropVoicemail_noActiveCall_returnsEmpty() {
        when(settings.isVoicemailDropEnabled()).thenReturn(true);
        when(settings.getVoicemailGreetingPath()).thenReturn("/greetings/vm.wav");
        when(telephony.playGreeting(any())).thenReturn(Optional.empty());

        assertThat(callService.dropVoicemail()).isEmpty();
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

    // ── finalizeWrapUp ──────────────────────────────────────────────────────────

    @Test
    void finalizeWrapUp_afterCallEnded_updatesPersistedRecord() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-wrap");
        stubOwnedNumber(LOCAL, LOCAL_ID);
        final Call persisted = aCall(Optional.empty(), Optional.empty(), Instant.parse("2020-01-01T00:00:00Z"));
        when(callRepo.save(any())).thenReturn(Result.ok(persisted));
        when(telephony.takeRecordingPath(any())).thenReturn(Optional.empty());
        when(callRepo.findById(new CallId(7L))).thenReturn(Optional.of(persisted));
        when(callRepo.update(any())).thenAnswer(inv -> Result.ok(inv.getArgument(0)));

        callService.dial(REMOTE, LOCAL);
        callService.onCallEnded("sip-wrap", "bye");
        callService.finalizeWrapUp("sip-wrap",
                Optional.of(new CallDisposition.Interested()), "Booked a demo");

        final ArgumentCaptor<Call> saved = ArgumentCaptor.forClass(Call.class);
        verify(callRepo, atLeastOnce()).update(saved.capture());
        assertThat(saved.getAllValues())
                .anySatisfy(c -> assertThat(c.disposition()).contains(new CallDisposition.Interested()));
        assertThat(saved.getAllValues())
                .anySatisfy(c -> assertThat(c.notes()).contains("Booked a demo"));
    }

    @Test
    void finalizeWrapUp_whileCallStillActive_doesNotTouchRepo() {
        when(telephony.dial(LOCAL, REMOTE)).thenReturn("sip-live");
        stubOwnedNumber(LOCAL, LOCAL_ID);

        callService.dial(REMOTE, LOCAL);
        callService.finalizeWrapUp("sip-live",
                Optional.of(new CallDisposition.Busy()), "note");

        verify(callRepo, never()).update(any());
    }

    @Test
    void finalizeWrapUp_unknownCall_isNoOp() {
        callService.finalizeWrapUp("nope", Optional.empty(), "note");

        verify(callRepo, never()).update(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Call aCall(Optional<CallDisposition> disposition, Optional<String> notes, Instant updatedAt) {
        final Instant started = Instant.parse("2020-01-01T00:00:00Z");
        return new Call(
                new CallId(7L), CallDirection.OUTBOUND, LOCAL_ID, Optional.empty(), REMOTE,
                disposition, started, Optional.of(started), Optional.of(started),
                Optional.of(0L), Optional.empty(), notes, started, updatedAt);
    }

    private void stubLeadDnc(PhoneNumber number, boolean isDnc) {
        var lead = new com.elitale.coldbirds.coldcalling.domain.model.Lead(
                new LeadId(1L),
                Optional.of("Test"), Optional.of("User"),
                number,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(),
                isDnc,
                Instant.now(), Instant.now()
        );
        when(leadRepo.findByPhone(number)).thenReturn(Optional.of(lead));
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
