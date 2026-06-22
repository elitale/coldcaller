package com.elitale.coldbirds.coldcalling.app;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioConfig;
import com.elitale.coldbirds.coldcalling.services.*;
import com.elitale.coldbirds.coldcalling.storage.DatabaseManager;
import com.elitale.coldbirds.coldcalling.storage.sqlite.*;
import com.elitale.coldbirds.coldcalling.telephony.TelephonyService;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceTester;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;
import com.elitale.coldbirds.coldcalling.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
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
 * SQLite {@code settings} table via {@link SettingsService}. They default to blank on
 * first run; the user configures them through the Settings screen.
 * The SIP stack and provider clients degrade gracefully when credentials are absent.
 */
public final class ColdCallingApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(ColdCallingApp.class);

    // Top-level owned resources — closed in stop()
    private DatabaseManager    db;
    private TelephonyService   telephonyService;
    private SmsService         smsService;
    private CallService        callService;
    private ContactService     contactService;
    private PhoneNumberService phoneNumberService;
    private PowerDialerService powerDialerService;
    private SettingsService    settingsService;
    private MainWindow         mainWindow;
    private AudioDeviceManager audioDeviceManager;
    private AudioDeviceTester  audioDeviceTester;

    public static void main(String[] args) {
        launch(args);
    }

    // ── JavaFX lifecycle ──────────────────────────────────────────────────────

    /**
     * Called on the JavaFX launcher thread before {@link #start}.
     * Performs all blocking initialisation (DB open, Flyway migration, STUN, SIP REGISTER).
     */
    @Override
    public void init() {
        LOG.info("coldCalling starting — wiring dependencies");

        try {
            // 1. Database
            db = new DatabaseManager();
            final var connection = db.connection();

            // 2. Repositories
            final var contactRepo     = new SqliteContactRepository(connection);
            final var phoneNumberRepo = new SqlitePhoneNumberRepository(connection);
            final var callRepo        = new SqliteCallRepository(connection);
            final var smsRepo         = new SqliteSmsRepository(connection);
            final var settingsRepo    = new SqliteSettingsRepository(connection);
            final var callListRepo    = new SqliteCallListRepository(connection);

            // 3. Settings — typed wrapper read by all providers and telephony wiring below.
            //    The app starts gracefully with no credentials configured.
            settingsService = new SettingsService(settingsRepo);

            // 4. Providers — credentials from settings table, not environment variables.
            final TwilioClient twilio = new TwilioClient(
                    TwilioConfig.of(settingsService.getTwilioAccountSid(),
                                    settingsService.getTwilioAuthToken()));

            // 5. Services
            contactService     = new ContactService(contactRepo);
            phoneNumberService = new PhoneNumberService(phoneNumberRepo, twilio, settingsRepo);
            smsService         = new SmsService(twilio, smsRepo, phoneNumberRepo, settingsService);

            // 6. Telephony — callService is the TelephonyListener.
            //    Circular dependency is broken with a two-step approach:
            //      a) build TelephonyService with a no-op listener,
            //      b) build CallService with the real TelephonyService,
            //      c) swap the listener on TelephonyService to point at CallService.
            final SipCredentials sipCreds = new SipCredentials(
                    settingsService.getSipUsername(),
                    settingsService.getSipPassword(),
                    settingsService.getSipDomain(),
                    settingsService.getSipProxy(),
                    settingsService.getSipProxyPort()
            );

            telephonyService = new TelephonyService(sipCreds, NOOP_LISTENER, null, null);
            callService      = new CallService(telephonyService, callRepo, contactRepo, phoneNumberRepo);
            telephonyService.setListener(callService);  // wire back: SIP events → CallService

            // 6a. Audio devices — enumerate, then apply the user's saved input/output (if any)
            //     to the telephony service so calls use the chosen devices (blank = OS default).
            audioDeviceManager = new AudioDeviceManager();
            audioDeviceTester  = new AudioDeviceTester();
            telephonyService.setAudioDevices(
                    audioDeviceManager.resolveInput(settingsService.getAudioInputDevice()).orElse(null),
                    audioDeviceManager.resolveOutput(settingsService.getAudioOutputDevice()).orElse(null));

            // 7. Power Dialer — dialCommand is a lambda capturing callService (safe: captured by
            //    reference; callService is fully initialised before any dial() is ever called).
            powerDialerService = new PowerDialerService(
                    callListRepo, contactRepo, phoneNumberService,
                    (remote, local) -> callService.dial(remote, local));

            // 8. Start telephony (STUN + SIP register) — non-blocking; errors logged
            try {
                telephonyService.start();
            } catch (Exception e) {
                LOG.error("Telephony start failed — app will run without SIP: {}", e.getMessage());
            }

            // 9. Sync owned numbers from Twilio (best-effort, non-fatal)
            if (!settingsService.getTwilioAccountSid().isBlank()
                    && !settingsService.getTwilioAuthToken().isBlank()) {
                phoneNumberService.fetchAndSync();
            }

            LOG.info("Dependency wiring complete");

        } catch (SQLException e) {
            LOG.error("Fatal: cannot open database: {}", e.getMessage(), e);
            Platform.exit();
        }
    }

    @Override
    public void start(Stage stage) {
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
                    phoneNumberService.getDefault().ifPresentOrElse(
                            owned -> callService.dial(remote, owned.number()),
                            () -> {
                                String msg = "Cannot dial " + rawNumber + " — no default number "
                                        + "configured. Set a default number in Settings.";
                                LOG.warn(msg);
                                notifyError(msg);
                            }
                    );
                });

        mainWindow = new MainWindow(stage, new MainWindow.Dependencies(
                contactService, callService, smsService, phoneNumberService,
                onDial, powerDialerService, settingsService,
                audioDeviceManager, audioDeviceTester, applyAudioDevices()));

        // Wire call events → MainWindow + PowerDialerService (composed lambdas)
        callService.setOnIncomingCall((callId, caller, called) ->
                mainWindow.showIncomingCall(
                        caller.value(), caller.value(),
                        () -> callService.answer(callId),
                        () -> callService.hangUp()
                )
        );

        callService.setOnCallAnswered(callId -> {
            mainWindow.showActiveCall(caller(callId), Instant.now(), () -> {
                callService.hangUp();
                mainWindow.showDialer();
            });
            powerDialerService.notifyCallAnswered(callId);
        });

        callService.setOnCallEnded((callId, reason) -> {
            mainWindow.showDialer();
            powerDialerService.notifyCallEnded(callId, reason);
        });

        mainWindow.show();

        // Begin polling Twilio for inbound SMS; persisted messages refresh the Messages view.
        smsService.startReceiving(sms -> mainWindow.refreshMessages());
    }

    /** Surface an error to the user as a toast (no-op if the window isn't up yet). */
    private void notifyError(String message) {
        if (mainWindow != null) {
            mainWindow.showError(message);
        }
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
