package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;

import java.util.Objects;

/**
 * Typed wrapper over {@link SettingsRepository}.
 * Provides strongly-typed getters/setters with documented defaults for every
 * application setting key. All reads fall back to a safe default when the key
 * is absent or unparseable.
 */
public final class SettingsService {

    // ── Key constants ─────────────────────────────────────────────────────────

    public static final String KEY_TELNYX_API_KEY            = "telnyx.api_key";
    public static final String KEY_SIP_USERNAME              = "sip.username";
    public static final String KEY_SIP_PASSWORD              = "sip.password";
    public static final String KEY_SIP_DOMAIN                = "sip.domain";
    public static final String KEY_SIP_PROXY                 = "sip.proxy";
    public static final String KEY_SIP_PROXY_PORT            = "sip.proxy_port";
    public static final String KEY_SMS_RELAY_URL             = "sms.relay_url";
    public static final String KEY_SMS_RELAY_KEY             = "sms.relay_key";
    public static final String KEY_AUDIO_INPUT_DEVICE        = "audio.input_device";
    public static final String KEY_AUDIO_OUTPUT_DEVICE       = "audio.output_device";
    public static final String KEY_AUDIO_JITTER_BUFFER_MS    = "audio.jitter_buffer_ms";
    public static final String KEY_APPEARANCE_THEME          = "appearance.theme";
    public static final String KEY_DIALER_NO_ANSWER_TIMEOUT  = "dialer.no_answer_timeout_sec";
    public static final String KEY_DIALER_AUTO_ADVANCE_DELAY = "dialer.auto_advance_delay_sec";
    public static final String KEY_DIALER_VOICEMAIL_DROP     = "dialer.voicemail_drop_enabled";

    // ── Defaults ──────────────────────────────────────────────────────────────

    private static final String DEFAULT_SIP_DOMAIN      = "sip.telnyx.com";
    private static final String DEFAULT_SIP_PROXY       = "sip.telnyx.com";
    private static final int    DEFAULT_SIP_PROXY_PORT  = 5060;
    private static final int    DEFAULT_JITTER_MS       = 40;
    private static final int    DEFAULT_NO_ANSWER_SEC   = 30;
    private static final int    DEFAULT_ADVANCE_SEC     = 1;
    private static final String DEFAULT_THEME           = "system";

    private final SettingsRepository repo;

    public SettingsService(SettingsRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
    }

    // ── Raw access ────────────────────────────────────────────────────────────

    /** Return the raw string value for {@code key}, or {@code fallback} when absent. */
    public String get(String key, String fallback) {
        return repo.get(key).orElse(fallback);
    }

    /** Persist a raw string value for {@code key}. */
    public void set(String key, String value) {
        repo.set(Objects.requireNonNull(key), Objects.requireNonNull(value));
    }

    // ── Telnyx ────────────────────────────────────────────────────────────────

    public String getTelnyxApiKey() {
        return get(KEY_TELNYX_API_KEY, "");
    }

    public void setTelnyxApiKey(String apiKey) {
        repo.set(KEY_TELNYX_API_KEY, Objects.requireNonNull(apiKey));
    }

    // ── SIP ───────────────────────────────────────────────────────────────────

    public String getSipUsername() { return get(KEY_SIP_USERNAME, ""); }
    public void   setSipUsername(String v) { repo.set(KEY_SIP_USERNAME, Objects.requireNonNull(v)); }

    public String getSipPassword() { return get(KEY_SIP_PASSWORD, ""); }
    public void   setSipPassword(String v) { repo.set(KEY_SIP_PASSWORD, Objects.requireNonNull(v)); }

    public String getSipDomain() { return get(KEY_SIP_DOMAIN, DEFAULT_SIP_DOMAIN); }
    public void   setSipDomain(String v) { repo.set(KEY_SIP_DOMAIN, Objects.requireNonNull(v)); }

    public String getSipProxy() { return get(KEY_SIP_PROXY, DEFAULT_SIP_PROXY); }
    public void   setSipProxy(String v) { repo.set(KEY_SIP_PROXY, Objects.requireNonNull(v)); }

    public int  getSipProxyPort() {
        return parseInt(get(KEY_SIP_PROXY_PORT, String.valueOf(DEFAULT_SIP_PROXY_PORT)),
                DEFAULT_SIP_PROXY_PORT);
    }
    public void setSipProxyPort(int port) { repo.set(KEY_SIP_PROXY_PORT, String.valueOf(port)); }

    // ── SMS Relay ─────────────────────────────────────────────────────────────

    public String getSmsRelayUrl() { return get(KEY_SMS_RELAY_URL, ""); }
    public void   setSmsRelayUrl(String v) { repo.set(KEY_SMS_RELAY_URL, Objects.requireNonNull(v)); }

    public String getSmsRelayKey() { return get(KEY_SMS_RELAY_KEY, ""); }
    public void   setSmsRelayKey(String v) { repo.set(KEY_SMS_RELAY_KEY, Objects.requireNonNull(v)); }

    // ── Audio ─────────────────────────────────────────────────────────────────

    public String getAudioInputDevice() { return get(KEY_AUDIO_INPUT_DEVICE, ""); }
    public void   setAudioInputDevice(String v) { repo.set(KEY_AUDIO_INPUT_DEVICE, Objects.requireNonNull(v)); }

    public String getAudioOutputDevice() { return get(KEY_AUDIO_OUTPUT_DEVICE, ""); }
    public void   setAudioOutputDevice(String v) { repo.set(KEY_AUDIO_OUTPUT_DEVICE, Objects.requireNonNull(v)); }

    public int  getJitterBufferMs() {
        return parseInt(get(KEY_AUDIO_JITTER_BUFFER_MS, String.valueOf(DEFAULT_JITTER_MS)),
                DEFAULT_JITTER_MS);
    }
    public void setJitterBufferMs(int ms) { repo.set(KEY_AUDIO_JITTER_BUFFER_MS, String.valueOf(ms)); }

    // ── Appearance ────────────────────────────────────────────────────────────

    public String getTheme() { return get(KEY_APPEARANCE_THEME, DEFAULT_THEME); }
    public void   setTheme(String theme) { repo.set(KEY_APPEARANCE_THEME, Objects.requireNonNull(theme)); }

    // ── Power Dialer ──────────────────────────────────────────────────────────

    public int  getNoAnswerTimeoutSec() {
        return parseInt(get(KEY_DIALER_NO_ANSWER_TIMEOUT, String.valueOf(DEFAULT_NO_ANSWER_SEC)),
                DEFAULT_NO_ANSWER_SEC);
    }
    public void setNoAnswerTimeoutSec(int sec) { repo.set(KEY_DIALER_NO_ANSWER_TIMEOUT, String.valueOf(sec)); }

    public int  getAutoAdvanceDelaySec() {
        return parseInt(get(KEY_DIALER_AUTO_ADVANCE_DELAY, String.valueOf(DEFAULT_ADVANCE_SEC)),
                DEFAULT_ADVANCE_SEC);
    }
    public void setAutoAdvanceDelaySec(int sec) { repo.set(KEY_DIALER_AUTO_ADVANCE_DELAY, String.valueOf(sec)); }

    public boolean isVoicemailDropEnabled() {
        return Boolean.parseBoolean(get(KEY_DIALER_VOICEMAIL_DROP, "false"));
    }
    public void setVoicemailDropEnabled(boolean enabled) {
        repo.set(KEY_DIALER_VOICEMAIL_DROP, String.valueOf(enabled));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
