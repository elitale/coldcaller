package com.elitale.coldbirds.coldcalling.app;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PowerDialerState;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioConfig;
import com.elitale.coldbirds.coldcalling.services.*;
import com.elitale.coldbirds.coldcalling.storage.DatabaseManager;
import com.elitale.coldbirds.coldcalling.storage.sqlite.*;
import com.elitale.coldbirds.coldcalling.telephony.TelephonyService;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceTester;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipTester;
import com.elitale.coldbirds.coldcalling.storage.repository.*;
import com.elitale.coldbirds.coldcalling.ui.MainWindow;
import com.elitale.coldbirds.coldcalling.ui.OnboardingWindow;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Application entry point.
 * <p>
 * Responsibility: launch the JavaFX runtime, perform dependency-injection wiring
 * in {@link #init()}, and hand the primary {@link Stage} to {@link MainWindow}.
 * No business logic lives here.
 * <p>
 * All credentials (Twilio Account SID + Auth Token, SIP credentials) are read from the
 * SQLite {@code settings} table via {@link SettingsService}. On first run they are blank
 * and the user configures them through the {@link OnboardingWindow} setup wizard, after
 * which {@link #launchMainApp(Stage)} builds the credential-dependent services.
 * The SIP stack and provider clients degrade gracefully when credentials are absent.
 */
public final class ColdCallingApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(ColdCallingApp.class);

    // Top-level owned resources — closed in stop()
    private DatabaseManager    db;
    private TelephonyService   telephonyService;
    private SmsService         smsService;
    private CallService        callService;
    private LeadService        leadService;
    private CallListService    callListService;
    private LeadImportService  leadImportService;
    private PhoneNumberService phoneNumberService;
    private PowerDialerService powerDialerService;
    private SettingsService    settingsService;
    private OnboardingService  onboardingService;
    private MainWindow         mainWindow;
    private AudioDeviceManager audioDeviceManager;
    private AudioDeviceTester  audioDeviceTester;

    /** SIP Call-ID of the call currently shown on the calling/wrap-up screen, if any. */
    private volatile String activeCallId;

    /** SIP Call-ID of the call being ended by a hands-free voicemail drop, if any. */
    private volatile String voicemailDropCallId;

    // Repositories — built once in init(), reused by launchMainApp()
    private LeadRepository        leadRepo;
    private PhoneNumberRepository phoneNumberRepo;
    private CallRepository        callRepo;
    private SmsRepository         smsRepo;
    private SettingsRepository    settingsRepo;
    private CallListRepository    callListRepo;

    public static void main(String[] args) {
        launch(args);
    }

    // ── JavaFX lifecycle ──────────────────────────────────────────────────────

    /**
     * Called on the JavaFX launcher thread before {@link #start}.
     * Performs credential-independent wiring: DB open, Flyway migration, repositories,
     * settings, audio enumeration, and the onboarding service. Credential-dependent
     * services (provider clients, telephony) are built later in {@link #launchMainApp}.
     */
    @Override
    public void init() {
        LOG.info("coldCalling starting — wiring dependencies");

        try {
            // 1. Database
            db = new DatabaseManager();
            final var connection = db.connection();

            // 2. Repositories
            leadRepo        = new SqliteLeadRepository(connection);
            phoneNumberRepo = new SqlitePhoneNumberRepository(connection);
            callRepo        = new SqliteCallRepository(connection);
            smsRepo         = new SqliteSmsRepository(connection);
            settingsRepo    = new SqliteSettingsRepository(connection);
            callListRepo    = new SqliteCallListRepository(connection);

            // 3. Settings — typed wrapper read by all providers and telephony wiring below.
            settingsService = new SettingsService(settingsRepo);

            // 4. Credential-independent services usable before onboarding.
            leadService = new LeadService(leadRepo);
            callListService = new CallListService(callListRepo);
            leadImportService = new LeadImportService(
                    new PhoneNormalizer(),
                    new SqliteLeadImportRepository(connection),
                    new SqliteImportBatchRepository(connection),
                    callListRepo);
            audioDeviceManager = new AudioDeviceManager();
            audioDeviceTester  = new AudioDeviceTester();

            // 5. Onboarding — drives the first-run wizard. Uses a transient Twilio client
            //    factory (default TwilioClient::new) so credentials can be tested before
            //    any are persisted. The PhoneNumberService here is only used to persist the
            //    chosen numbers (repo writes); its Twilio client is irrelevant to that path.
            final PhoneNumberService onboardingNumbers =
                    new PhoneNumberService(phoneNumberRepo,
                            new TwilioClient(TwilioConfig.of("", "")), settingsRepo);
            onboardingService = new OnboardingService(settingsService, onboardingNumbers, new SipTester());

            LOG.info("Dependency wiring complete");

        } catch (SQLException e) {
            LOG.error("Fatal: cannot open database: {}", e.getMessage(), e);
            Platform.exit();
        }
    }

    @Override
    public void start(Stage stage) {
        if (onboardingService.isOnboardingComplete()) {
            launchMainApp(stage);
        } else {
            LOG.info("First run — showing onboarding wizard");
            new OnboardingWindow(stage, onboardingService).show(() -> launchMainApp(stage));
        }
    }

    /**
     * Build and start the credential-dependent services from the now-persisted
     * settings, then show the main window. Invoked either directly (when onboarding
     * was already completed) or as the onboarding wizard's completion callback.
     * Must run on the FX Application Thread.
     */
    private void launchMainApp(Stage stage) {
        // Providers — credentials from settings table (written by onboarding or Settings).
        final TwilioClient twilio = new TwilioClient(
                TwilioConfig.of(settingsService.getTwilioAccountSid(),
                                settingsService.getTwilioAuthToken()));

        // Services
        phoneNumberService = new PhoneNumberService(phoneNumberRepo, twilio, settingsRepo);
        smsService         = new SmsService(twilio, smsRepo, phoneNumberRepo, settingsService);

        // Telephony — callService is the TelephonyListener. The circular dependency is
        // broken with a two-step approach (no-op listener, then setListener()).
        final SipCredentials sipCreds = new SipCredentials(
                settingsService.getSipUsername(),
                settingsService.getSipPassword(),
                settingsService.getSipDomain(),
                settingsService.getSipProxy(),
                settingsService.getSipProxyPort()
        );

        telephonyService = new TelephonyService(sipCreds, NOOP_LISTENER, null, null);
        callService      = new CallService(telephonyService, callRepo, leadRepo, phoneNumberRepo, settingsService);
        telephonyService.setListener(callService);  // wire back: SIP events → CallService
        // Resolve the remote number's country so recordings land under the right folder.
        telephonyService.setCountryResolver(
                number -> CountryLookup.byE164(CountryCatalog.ALL, number));

        // Audio devices — apply the user's saved input/output (blank = OS default).
        telephonyService.setAudioDevices(
                audioDeviceManager.resolveInput(settingsService.getAudioInputDevice()).orElse(null),
                audioDeviceManager.resolveOutput(settingsService.getAudioOutputDevice()).orElse(null));

        // Caller-ID selection: rotate across the active number pool, but stick to the
        // number a prospect was last reached on (derived from call history). Shared by
        // manual dialling and the power dialer so both behave identically.
        final CallerIdSelector callerIdSelector = new CallerIdSelector(phoneNumberService, callRepo);

        // Power Dialer
        powerDialerService = new PowerDialerService(
                callListRepo, leadRepo, callerIdSelector, settingsService,
                (remote, local) -> callService.dial(remote, local));

        // Seed the global Motion Doctrine gate from the saved preference.
        Motion.setReduced(settingsService.isReduceMotion());

        // Start telephony (STUN + SIP register) — non-blocking; errors logged.
        try {
            telephonyService.start();
        } catch (Exception e) {
            LOG.error("Telephony start failed — app will run without SIP: {}", e.getMessage());
        }

        // Sync owned numbers from Twilio (best-effort, non-fatal).
        if (!settingsService.getTwilioAccountSid().isBlank()
                && !settingsService.getTwilioAuthToken().isBlank()) {
            phoneNumberService.fetchAndSync();
        }

        // Build the single dial consumer — resolves default owned number then dispatches
        // to CallService on a background thread (never blocks the FX Application Thread).
        final Consumer<String> onDial = rawNumber ->
                CompletableFuture.runAsync(() -> {
                    PhoneNumber remote;
                    try {
                        remote = new PhoneNumber(rawNumber);
                    } catch (IllegalArgumentException e) {
                        String msg = "Cannot dial '" + rawNumber + "' — invalid number.";
                        LOG.warn("{} {}", msg, e.getMessage());
                        notifyError(msg);
                        return;
                    }
                    callerIdSelector.selectFor(remote).ifPresentOrElse(
                            owned -> callService.dial(remote, owned.number()),
                            () -> {
                                String msg = "Cannot dial " + rawNumber + " — no active calling "
                                        + "number. Turn on at least one number in Settings.";
                                LOG.warn(msg);
                                notifyError(msg);
                            }
                    );
                });

        mainWindow = new MainWindow(stage, new MainWindow.Dependencies(
                leadService, callListService, leadImportService, callService, smsService, phoneNumberService,
                onDial, powerDialerService, settingsService,
                new CallRoutingService(settingsService),
                audioDeviceManager, audioDeviceTester, applyAudioDevices(),
                switchAudioDevices()));

        // Wire call events → MainWindow + PowerDialerService (composed lambdas)
        callService.setOnIncomingCall((callId, caller, called) -> {
            activeCallId = callId;
            mainWindow.showIncomingCall(
                    caller.value(), caller.value(),
                    () -> callService.answer(callId),
                    () -> callService.hangUp()
            );
        });

        // Continuously persist notes + disposition the rep types on the calling/wrap-up
        // screen so a call log is never lost if they forget to "Save & Close".
        mainWindow.setOnCallLogAutoSave((disposition, notes) -> {
            final String id = activeCallId;
            if (id != null) {
                CompletableFuture.runAsync(() -> callService.autoSaveWrapUp(id, disposition, notes));
            }
        });

        // During a power-dialer run, picking a disposition on the wrap-up screen finalises
        // the current call and advances to the next lead — hands-free triage. Outside a
        // running session this is a no-op (manual calls finalise via "Save & Close").
        mainWindow.setOnWrapUpDispositionChosen(() -> {
            final boolean running = powerDialerService.getCurrentSession()
                    .map(session -> session.state() instanceof PowerDialerState.Running)
                    .orElse(false);
            if (!running) return;
            final String id = activeCallId;
            if (id != null) {
                finalizeAndClose(id);
            }
            CompletableFuture.runAsync(powerDialerService::advance);
        });

        // Voicemail drop: the calling screen's Voicemail control (or the V key) invokes this
        // action, which plays the configured greeting into the live call and returns its
        // duration so the button can show a determinate "dropping" affordance.
        mainWindow.setOnVoicemailDrop(callService::dropVoicemail);

        // When the greeting finishes during a running power-dialer session, end the current
        // call and advance to the next lead — voicemail → drop → advance as one hands-free
        // flow. The actual advance happens when the resulting call-end event arrives (below),
        // so the outcome is recorded as Voicemail and the wrap-up screen is skipped. Manual
        // calls leave the line to the rep.
        mainWindow.setOnVoicemailCompleted(() -> {
            final boolean running = powerDialerService.getCurrentSession()
                    .map(session -> session.state() instanceof PowerDialerState.Running)
                    .orElse(false);
            final String id = activeCallId;
            if (running && id != null) {
                voicemailDropCallId = id;
                callService.hangUp();
            }
        });

        // Outbound: open the calling screen the instant the user presses call —
        // BEFORE the SIP INVITE is dispatched — so dialling feels immediate.
        callService.setOnCallStarting((remote, from) -> {
            activeCallId = null;
            mainWindow.showCallStarting(remote, from, () -> callService.hangUp());
        });

        // INVITE dispatched → flip the already-visible screen to "Ringing…".
        callService.setOnCallRinging(callId -> {
            activeCallId = callId;
            mainWindow.markCallRinging();
        });

        // Outbound that never started (not signed in, DNC, etc.): show the reason.
        callService.setOnCallFailed((remote, reason) -> {
            mainWindow.showCallFailed(remote, reason, closeToDialer());
            notifyError("Call failed: " + reason);
        });

        callService.setOnCallAnswered(callId -> {
            final boolean inbound = callService.getActiveCallDirection(callId)
                    .map(direction -> direction == CallDirection.INBOUND)
                    .orElse(false);
            if (inbound) {
                // Inbound: the incoming overlay was showing — open the calling screen now.
                mainWindow.showActiveCall(caller(callId), Instant.now(), () -> callService.hangUp());
            } else {
                // Outbound: the ringing screen is already up — just connect it.
                mainWindow.markCallConnected(Instant.now());
            }
            powerDialerService.notifyCallAnswered(callId);
        });

        callService.setOnCallEnded((callId, reason) -> {
            powerDialerService.notifyCallEnded(callId, reason);
            mainWindow.refreshRecentCalls();
            if (callId.equals(voicemailDropCallId)) {
                // Hands-free voicemail: record the outcome and advance, skipping wrap-up.
                voicemailDropCallId = null;
                finalizeVoicemailAndAdvance(callId);
                return;
            }
            if (reason != null && reason.startsWith(TelephonyService.FAILURE_PREFIX)) {
                // Failed call: keep the calling screen up and show the reason there.
                final String detail = reason.substring(TelephonyService.FAILURE_PREFIX.length());
                mainWindow.markCallFailed(detail, closeToDialer());
                notifyError("Call failed: " + detail);
            } else {
                // Natural end: stay on a wrap-up screen so the rep can finish logging.
                mainWindow.showCallWrapUp(Instant.now(), () -> finalizeAndClose(callId));
            }
        });

        mainWindow.show();
        mainWindow.refreshRecentCalls();

        // Twilio inbound SMS polling is disabled for now. To re-enable, restore:
        //   smsService.startReceiving(sms -> mainWindow.refreshMessages());
        // Until then, the Messages view refreshes when the user opens it. Numbers
        // can be re-synced on demand via the Refresh button in Settings.
    }

    /** Surface an error to the user as a toast (no-op if the window isn't up yet). */
    private void notifyError(String message) {
        if (mainWindow != null) {
            mainWindow.showError(message);
        }
    }

    /**
     * Build the dismiss action for the failed/cancelled calling screen:
     * stop timers/tones and return to the dialer.
     */
    private Runnable closeToDialer() {
        return () -> {
            activeCallId = null;
            mainWindow.endActiveCall();
            mainWindow.showDialer();
        };
    }

    /**
     * Finalise a call from the wrap-up screen: persist the disposition and notes
     * the rep entered after the line dropped, then return to the dialer.
     */
    private void finalizeAndClose(String callId) {
        callService.finalizeWrapUp(callId, mainWindow.selectedDisposition(), mainWindow.callNotes());
        activeCallId = null;
        mainWindow.endActiveCall();
        mainWindow.showDialer();
    }

    /**
     * Finalise a call that ended via a voicemail drop during a power-dialer run:
     * record the Voicemail disposition, return to the dialer, and advance to the
     * next lead — no wrap-up screen. Runs on the call-ended (JAIN-SIP) thread;
     * the call record is already persisted by the time this fires.
     */
    private void finalizeVoicemailAndAdvance(String callId) {
        callService.finalizeWrapUp(callId, Optional.of(new CallDisposition.Voicemail()), "");
        activeCallId = null;
        mainWindow.endActiveCall();
        mainWindow.showDialer();
        CompletableFuture.runAsync(powerDialerService::advance);
    }

    /**
     * Callback the Settings screen invokes when the user saves audio devices: resolves the
     * persisted device ids to mixers and applies them to telephony for the next call.
     */
    private BiConsumer<String, String> applyAudioDevices() {
        return (inputId, outputId) -> telephonyService.setAudioDevices(
                audioDeviceManager.resolveInput(inputId).orElse(null),
                audioDeviceManager.resolveOutput(outputId).orElse(null));
    }

    /**
     * Callback the in-call audio menu invokes to switch devices mid-call: resolves the
     * ids and applies them to the live call on a background thread (opening audio lines
     * blocks the caller), then writes them through to settings so they persist for future
     * calls.
     */
    private BiConsumer<String, String> switchAudioDevices() {
        return (inputId, outputId) -> CompletableFuture.runAsync(() -> {
            telephonyService.switchAudioDevices(
                    audioDeviceManager.resolveInput(inputId).orElse(null),
                    audioDeviceManager.resolveOutput(outputId).orElse(null));
            settingsService.setAudioInputDevice(inputId);
            settingsService.setAudioOutputDevice(outputId);
        });
    }

    @Override
    public void stop() {
        LOG.info("coldCalling shutting down");
        if (smsService         != null) smsService.stopReceiving();
        if (telephonyService   != null) telephonyService.close();
        if (powerDialerService != null) powerDialerService.close();
        try {
            if (db != null) db.close();
        } catch (Exception e) {
            LOG.warn("Error closing database: {}", e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * No-op listener used only during TelephonyService construction before
     * CallService has been built and wired back via {@code setListener()}.
     */
    private static final TelephonyService.TelephonyListener NOOP_LISTENER =
            new TelephonyService.TelephonyListener() {
                @Override public void onIncomingCall(String id, PhoneNumber c, PhoneNumber d) {}
                @Override public void onCallAnswered(String id) {}
                @Override public void onCallEnded(String id, String r) {}
                @Override public void onRegistrationChanged(boolean reg) {}
            };

    /**
     * Returns the remote party E.164 number for an active call, falling back
     * to the raw callId if the call cannot be found (should not happen in practice).
     */
    private String caller(String callId) {
        return callService.getActiveCallRemote(callId).orElse(callId);
    }
}
