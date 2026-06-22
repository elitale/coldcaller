package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.onboarding.ProviderOptions;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingMode;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioConfig;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioVoiceBridgeProvisioner;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Manages outbound call-routing (PSTN bridge) configuration: the provider Voice
 * webhook that bridges a registered SIP client's outbound call to the phone network.
 *
 * <p>Two ways to configure, both of which <em>apply</em> the URL to the provider
 * (not merely store it) so the SIP domain actually routes calls:
 * <ul>
 *   <li><b>AUTO</b> — deploy a PSTN bridge into the user's own Twilio account and
 *       point the SIP domain at that per-account function URL.</li>
 *   <li><b>MANUAL</b> — apply a user-supplied bridge URL.</li>
 * </ul>
 *
 * <p>Only Twilio can be applied via API today; for other providers the config is
 * stored for reference and the user wires the webhook in their provider console.
 */
public final class CallRoutingService {

    private final SettingsService settings;
    private final Function<TwilioConfig, TwilioClient> twilioFactory;
    private final Function<TwilioConfig, TwilioVoiceBridgeProvisioner> provisionerFactory;

    public CallRoutingService(SettingsService settings,
                              Function<TwilioConfig, TwilioClient> twilioFactory,
                              Function<TwilioConfig, TwilioVoiceBridgeProvisioner> provisionerFactory) {
        this.settings           = Objects.requireNonNull(settings, "settings must not be null");
        this.twilioFactory      = Objects.requireNonNull(twilioFactory, "twilioFactory must not be null");
        this.provisionerFactory = Objects.requireNonNull(provisionerFactory, "provisionerFactory must not be null");
    }

    /** Convenience constructor — inject the Twilio factory; AUTO uses a real provisioner. */
    public CallRoutingService(SettingsService settings,
                              Function<TwilioConfig, TwilioClient> twilioFactory) {
        this(settings, twilioFactory, TwilioVoiceBridgeProvisioner::new);
    }

    /** Convenience constructor using real Twilio collaborators. */
    public CallRoutingService(SettingsService settings) {
        this(settings, TwilioClient::new, TwilioVoiceBridgeProvisioner::new);
    }

    /** Read the persisted routing config, falling back to NONE on absent/invalid state. */
    public CallRoutingConfig load() {
        final String providerId = settings.getCallRoutingProvider();
        try {
            return new CallRoutingConfig(
                    providerId,
                    parseMode(settings.getCallRoutingMode()),
                    settings.getCallRoutingVoiceUrl(),
                    settings.getCallRoutingCallerIdFallback());
        } catch (RuntimeException invalidStoredState) {
            return CallRoutingConfig.none(providerId);
        }
    }

    /** Persist a routing config (no provider API call). */
    public void save(CallRoutingConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        settings.setCallRoutingProvider(config.providerId());
        settings.setCallRoutingMode(config.mode().name().toLowerCase(Locale.ROOT));
        settings.setCallRoutingVoiceUrl(config.voiceUrl());
        settings.setCallRoutingCallerIdFallback(config.callerIdFallback());
    }

    /** Apply a user-supplied bridge URL (MANUAL): push to the provider when possible, then persist. */
    public Result<CallRoutingConfig> applyManual(String providerId, String voiceUrl, String callerIdFallback) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(voiceUrl, "voiceUrl must not be null");
        Objects.requireNonNull(callerIdFallback, "callerIdFallback must not be null");
        final CallRoutingConfig config;
        try {
            config = new CallRoutingConfig(
                    providerId, CallRoutingMode.MANUAL, voiceUrl.trim(), callerIdFallback.trim());
        } catch (RuntimeException invalid) {
            return Result.err(invalid.getMessage());
        }
        return apply(config);
    }

    /**
     * Deploy a PSTN bridge into the user's own Twilio account (AUTO), point the SIP
     * domain at the resulting per-account URL, then persist the config.
     */
    public Result<CallRoutingConfig> autoConfigure(String providerId) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (!supportsAutoRouting(providerId)) {
            return Result.err("Automatic routing isn't available for " + providerId
                    + " yet. Choose Manual and paste your bridge URL.");
        }
        final TwilioConfig twilioConfig = twilioConfig();
        if (!twilioConfig.isConfigured()) {
            return Result.err("Twilio isn't configured yet — add your Account SID and Auth Token first.");
        }
        final Result<String> bridge = provisionerFactory.apply(twilioConfig).provisionBridge();
        if (bridge instanceof Result.Err<String> err) {
            return Result.err(err.message(), err.cause());
        }
        final String bridgeUrl = ((Result.Ok<String>) bridge).value();
        final CallRoutingConfig config;
        try {
            config = new CallRoutingConfig(providerId, CallRoutingMode.AUTO, bridgeUrl,
                    settings.getCallRoutingCallerIdFallback().trim());
        } catch (RuntimeException invalid) {
            return Result.err(invalid.getMessage());
        }
        return apply(config);
    }

    /** Read the bridge URL currently live on the provider (Twilio only). */
    public Result<Optional<String>> currentVoiceUrl(String providerId) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (!ProviderOptions.TWILIO_ID.equals(providerId)) {
            return Result.err("Reading the live bridge URL is only supported for Twilio.");
        }
        final TwilioConfig twilioConfig = twilioConfig();
        if (!twilioConfig.isConfigured()) {
            return Result.err("Twilio isn't configured yet — add your Account SID and Auth Token first.");
        }
        return twilioFactory.apply(twilioConfig).readSipDomainVoiceUrl(settings.getSipDomain());
    }

    /** Whether this provider supports one-click AUTO routing today. */
    public boolean supportsAutoRouting(String providerId) {
        return ProviderOptions.TWILIO_ID.equals(providerId);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Result<CallRoutingConfig> apply(CallRoutingConfig config) {
        if (ProviderOptions.TWILIO_ID.equals(config.providerId())) {
            final Result<Void> applied = applyToTwilio(config.voiceUrl());
            if (applied instanceof Result.Err<Void> err) {
                return Result.err(err.message(), err.cause());
            }
        }
        save(config);
        return Result.ok(config);
    }

    private Result<Void> applyToTwilio(String voiceUrl) {
        final TwilioConfig twilioConfig = twilioConfig();
        if (!twilioConfig.isConfigured()) {
            return Result.err("Twilio isn't configured yet — add your Account SID and Auth Token first.");
        }
        return twilioFactory.apply(twilioConfig).setSipDomainVoiceUrl(settings.getSipDomain(), voiceUrl);
    }

    private TwilioConfig twilioConfig() {
        return TwilioConfig.of(settings.getTwilioAccountSid(), settings.getTwilioAuthToken());
    }

    private static CallRoutingMode parseMode(String raw) {
        try {
            return CallRoutingMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return CallRoutingMode.NONE;
        }
    }
}
