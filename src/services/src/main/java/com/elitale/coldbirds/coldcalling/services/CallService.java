package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository.NewCall;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.telephony.TelephonyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Orchestrates call lifecycle: DNC gating, telephony dispatch, call record persistence.
 * <p>
 * Implements {@link TelephonyService.TelephonyListener} to receive SIP callbacks and
 * re-emits them to the UI layer via registered callbacks.
 * <p>
 * Threading: callbacks from {@link TelephonyService} arrive on the JAIN-SIP internal
 * thread. UI callbacks ({@link #setOnIncomingCall}, etc.) are fired on that same thread;
 * callers must dispatch to the FX Application Thread via {@code Platform.runLater()}.
 */
public final class CallService implements TelephonyService.TelephonyListener {

    private static final Logger LOG = LoggerFactory.getLogger(CallService.class);

    /** Functional interface for incoming-call notifications (callId, caller, called). */
    @FunctionalInterface
    public interface IncomingCallListener {
        void onIncomingCall(String callId, PhoneNumber callerNumber, PhoneNumber calledNumber);
    }

    /** Mutable state for a call that has started but not yet ended. */
    private static final class ActiveCall {
        final String              sipCallId;
        final PhoneNumber         remoteNumber;
        final Optional<PhoneNumberId> localNumberId;
        final Optional<ContactId> contactId;
        final CallDirection       direction;
        final Instant             startedAt;
        volatile Instant          answeredAt;
        volatile CallDisposition  disposition;
        volatile String           notes = "";

        ActiveCall(String sipCallId, PhoneNumber remoteNumber,
                   Optional<PhoneNumberId> localNumberId, Optional<ContactId> contactId,
                   CallDirection direction, Instant startedAt) {
            this.sipCallId     = sipCallId;
            this.remoteNumber  = remoteNumber;
            this.localNumberId = localNumberId;
            this.contactId     = contactId;
            this.direction     = direction;
            this.startedAt     = startedAt;
        }
    }

    private final TelephonyService      telephony;
    private final CallRepository        callRepo;
    private final ContactRepository     contactRepo;
    private final PhoneNumberRepository phoneNumberRepo;

    /** Calls that have started but not yet ended, keyed by SIP Call-ID. */
    private final ConcurrentHashMap<String, ActiveCall> activeCalls = new ConcurrentHashMap<>();

    // UI callbacks
    private IncomingCallListener          onIncomingCallCb   = (id, a, b) -> {};
    private Consumer<String>              onCallRingingCb    = id -> {};
    private Consumer<String>              onCallAnsweredCb   = id -> {};
    private BiConsumer<String, String>    onCallEndedCb      = (id, r) -> {};
    private BiConsumer<String, String>    onCallFailedCb     = (n, r) -> {};
    private Consumer<Boolean>             onRegistrationCb   = reg -> {};

    public CallService(
            TelephonyService      telephony,
            CallRepository        callRepo,
            ContactRepository     contactRepo,
            PhoneNumberRepository phoneNumberRepo) {
        this.telephony       = Objects.requireNonNull(telephony,       "telephony must not be null");
        this.callRepo        = Objects.requireNonNull(callRepo,        "callRepo must not be null");
        this.contactRepo     = Objects.requireNonNull(contactRepo,     "contactRepo must not be null");
        this.phoneNumberRepo = Objects.requireNonNull(phoneNumberRepo, "phoneNumberRepo must not be null");
    }

    // ── Callback registration ─────────────────────────────────────────────────

    public void setOnIncomingCall(IncomingCallListener cb) {
        this.onIncomingCallCb = Objects.requireNonNull(cb);
    }

    /**
     * Register a callback fired the instant an outbound call begins ringing
     * (immediately after the INVITE is dispatched, before the remote party
     * answers). The argument is the SIP Call-ID. Fired on the dialling thread;
     * callers must dispatch UI work to the FX Application Thread.
     */
    public void setOnCallRinging(Consumer<String> cb) {
        this.onCallRingingCb = Objects.requireNonNull(cb);
    }

    public void setOnCallAnswered(Consumer<String> cb) {
        this.onCallAnsweredCb = Objects.requireNonNull(cb);
    }

    public void setOnCallEnded(BiConsumer<String, String> cb) {
        this.onCallEndedCb = Objects.requireNonNull(cb);
    }

    /**
     * Register a callback fired when an outbound call cannot even start
     * (DNC-blocked, no usable local number, or the SIP stack rejected the
     * INVITE before a Call-ID existed). Arguments are the remote E.164 number
     * and a human-readable reason. Fired on the dialling thread.
     */
    public void setOnCallFailed(BiConsumer<String, String> cb) {
        this.onCallFailedCb = Objects.requireNonNull(cb);
    }

    public void setOnRegistrationChanged(Consumer<Boolean> cb) {
        this.onRegistrationCb = Objects.requireNonNull(cb);
    }

    // ── Call control API ──────────────────────────────────────────────────────

    /**
     * Initiate an outbound call after DNC and number-ownership checks.
     *
     * @param remote E.164 number to dial
     * @param local  owned number to show as caller-ID
     */
    public void dial(PhoneNumber remote, PhoneNumber local) {
        Objects.requireNonNull(remote, "remote must not be null");
        Objects.requireNonNull(local,  "local must not be null");

        if (isDnc(remote)) {
            LOG.warn("Blocked outbound dial to DNC number {}", remote.value());
            onCallFailedCb.accept(remote.value(), "This number is on your Do-Not-Call list.");
            return;
        }

        final Optional<OwnedNumber> owned = phoneNumberRepo.findByNumber(local);
        if (owned.isEmpty()) {
            LOG.error("Cannot dial: local number {} is not an owned number", local.value());
            onCallFailedCb.accept(remote.value(),
                    "Your calling number isn’t set up to place calls. Check Settings.");
            return;
        }

        final String sipCallId = telephony.dial(local, remote);
        if (sipCallId.isBlank()) {
            LOG.error("Telephony returned blank call-id for dial to {}", remote.value());
            onCallFailedCb.accept(remote.value(),
                    "Couldn’t start the call. Check your internet connection and SIP sign-in in Settings.");
            return;
        }

        final Optional<ContactId> contactId = contactRepo.findByPhone(remote)
                .map(c -> c.id());

        activeCalls.put(sipCallId, new ActiveCall(
                sipCallId, remote, Optional.of(owned.get().id()), contactId,
                CallDirection.OUTBOUND, Instant.now()
        ));
        LOG.info("Outbound call {} started → {}", sipCallId, remote.value());
        onCallRingingCb.accept(sipCallId);
    }

    /**
     * Answer an inbound call.
     *
     * @param callId SIP Call-ID from {@link IncomingCallListener}
     */
    public void answer(String callId) {
        telephony.answer(Objects.requireNonNull(callId, "callId must not be null"));
    }

    /** Hang up the current active call. */
    public void hangUp() {
        telephony.hangUp();
    }

    /**
     * Set the disposition on the active call (called by the UI before hang-up
     * or after the call ends).
     *
     * @param callId     SIP Call-ID
     * @param disposition outcome to record
     */
    public void setDisposition(String callId, CallDisposition disposition) {
        final ActiveCall call = activeCalls.get(callId);
        if (call != null) call.disposition = disposition;
    }

    /**
     * Set notes on the active call.
     *
     * @param callId SIP Call-ID
     * @param notes  free-text notes
     */
    public void setNotes(String callId, String notes) {
        final ActiveCall call = activeCalls.get(callId);
        if (call != null) call.notes = (notes != null) ? notes : "";
    }

    /**
     * Retroactively set the disposition on an already-persisted call record.
     * Read-modify-writes the {@link Call} and bumps its {@code updatedAt}.
     * Safe to call from a background thread.
     *
     * @param id          the persisted call's id
     * @param disposition the outcome to record
     * @return the updated call, or an error if the call is not found
     */
    public Result<Call> updateDisposition(CallId id, CallDisposition disposition) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        final Optional<Call> existing = callRepo.findById(id);
        if (existing.isEmpty()) {
            return Result.err("Call not found: " + id.value());
        }
        return callRepo.update(withDisposition(existing.get(), disposition));
    }

    /**
     * Retroactively set the notes on an already-persisted call record.
     * A blank value clears the notes. Bumps {@code updatedAt}.
     * Safe to call from a background thread.
     *
     * @param id    the persisted call's id
     * @param notes free-text notes; blank clears them
     * @return the updated call, or an error if the call is not found
     */
    public Result<Call> updateNotes(CallId id, String notes) {
        Objects.requireNonNull(id, "id must not be null");
        final Optional<Call> existing = callRepo.findById(id);
        if (existing.isEmpty()) {
            return Result.err("Call not found: " + id.value());
        }
        final Optional<String> trimmed = (notes == null || notes.isBlank())
                ? Optional.empty()
                : Optional.of(notes.strip());
        return callRepo.update(withNotes(existing.get(), trimmed));
    }

    private static Call withDisposition(Call c, CallDisposition disposition) {
        return new Call(
                c.id(), c.direction(), c.phoneNumberId(), c.contactId(), c.remoteNumber(),
                Optional.of(disposition), c.startedAt(), c.answeredAt(), c.endedAt(),
                c.durationMs(), c.recordingPath(), c.notes(), c.createdAt(), Instant.now());
    }

    private static Call withNotes(Call c, Optional<String> notes) {
        return new Call(
                c.id(), c.direction(), c.phoneNumberId(), c.contactId(), c.remoteNumber(),
                c.disposition(), c.startedAt(), c.answeredAt(), c.endedAt(),
                c.durationMs(), c.recordingPath(), notes, c.createdAt(), Instant.now());
    }

    /**
     * Return the most recent call records, up to {@code limit} rows, newest first.
     * Does not block — safe to call from a background thread.
     *
     * @param limit maximum number of records to return
     */
    public List<Call> findRecent(int limit) {
        return callRepo.findRecent(limit);
    }

    /**
     * Return every call recorded against {@code remoteNumber}, newest first.
     * Does not block — safe to call from a background thread.
     *
     * @param remoteNumber the remote party's E.164 number
     */
    public List<Call> findByRemoteNumber(PhoneNumber remoteNumber) {
        return callRepo.findByRemoteNumber(Objects.requireNonNull(remoteNumber, "remoteNumber"));
    }

    /**
     * Returns the E.164 remote number for an in-flight call, falling back
     * to the raw callId if the call cannot be found (should not happen in practice).
     *
     * @param callId SIP Call-ID
     */
    public Optional<String> getActiveCallRemote(String callId) {
        final ActiveCall call = activeCalls.get(callId);
        return call != null ? Optional.of(call.remoteNumber.value()) : Optional.empty();
    }

    /**
     * Returns the direction of an in-flight call, or empty if no such call is active.
     * Used by the UI to decide whether a freshly-answered call already has its
     * calling screen on-screen (outbound) or needs it opened now (inbound).
     *
     * @param callId SIP Call-ID
     */
    public Optional<CallDirection> getActiveCallDirection(String callId) {
        final ActiveCall call = activeCalls.get(callId);
        return call != null ? Optional.of(call.direction) : Optional.empty();
    }

    // ── TelephonyService.TelephonyListener ───────────────────────────────────

    @Override
    public void onIncomingCall(String callId, PhoneNumber callerNumber, PhoneNumber calledNumber) {
        final Optional<OwnedNumber> owned = phoneNumberRepo.findByNumber(calledNumber);
        final Optional<PhoneNumberId> localId = owned.map(OwnedNumber::id);
        if (owned.isEmpty()) {
            LOG.warn("Inbound call {} arrived on unrecognised local number {}", callId, calledNumber.value());
        }

        final Optional<ContactId> contactId = contactRepo.findByPhone(callerNumber)
                .map(c -> c.id());

        activeCalls.put(callId, new ActiveCall(
                callId, callerNumber, localId, contactId,
                CallDirection.INBOUND, Instant.now()
        ));
        onIncomingCallCb.onIncomingCall(callId, callerNumber, calledNumber);
    }

    @Override
    public void onCallAnswered(String callId) {
        final ActiveCall call = activeCalls.get(callId);
        if (call != null) call.answeredAt = Instant.now();
        onCallAnsweredCb.accept(callId);
    }

    @Override
    public void onCallEnded(String callId, String reason) {
        final ActiveCall call = activeCalls.remove(callId);
        if (call != null) {
            persistCallRecord(call, reason);
        }
        onCallEndedCb.accept(callId, reason);
    }

    @Override
    public void onRegistrationChanged(boolean registered) {
        onRegistrationCb.accept(registered);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private boolean isDnc(PhoneNumber number) {
        return contactRepo.findByPhone(number)
                .map(c -> c.dnc())
                .orElse(false);
    }

    private void persistCallRecord(ActiveCall call, String reason) {
        if (call.localNumberId.isEmpty()) {
            LOG.warn("Cannot persist call record for {} — local number unknown", call.sipCallId);
            return;
        }

        final Instant endedAt    = Instant.now();
        final Instant answeredAt = (call.answeredAt != null) ? call.answeredAt : endedAt;
        final long    durationMs = endedAt.toEpochMilli() - answeredAt.toEpochMilli();

        final CallDisposition disposition = (call.disposition != null)
                ? call.disposition
                : mapReasonToDisposition(reason);

        final Optional<String> recordingPath = telephony.takeRecordingPath(call.sipCallId);

        final NewCall newCall = new NewCall(
                call.direction,
                call.localNumberId.get(),
                call.contactId,
                call.remoteNumber,
                Optional.of(disposition),
                call.startedAt,
                Optional.of(answeredAt),
                Optional.of(endedAt),
                Optional.of(durationMs),
                recordingPath,
                Optional.ofNullable(call.notes.isBlank() ? null : call.notes)
        );

        final Result<Call> saved = callRepo.save(newCall);
        switch (saved) {
            case Result.Ok<?>  ok  -> LOG.info("Call record saved for {}", call.sipCallId);
            case Result.Err<?> err -> LOG.error("Failed to save call record for {}: {}", call.sipCallId, err.message());
        }
    }

    private static CallDisposition mapReasonToDisposition(String reason) {
        return switch (reason) {
            case "bye"       -> new CallDisposition.NotInterested();
            case "no-answer" -> new CallDisposition.NoAnswer();
            case "busy"      -> new CallDisposition.Busy();
            default          -> new CallDisposition.Failed(reason);
        };
    }
}
