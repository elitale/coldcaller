package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingMode;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioVoiceBridgeProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallRoutingServiceTest {

    @Mock private SettingsService settings;
    @Mock private TwilioClient twilioClient;
    @Mock private TwilioVoiceBridgeProvisioner provisioner;

    private CallRoutingService service;

    private static final String URL    = "https://my-bridge.twil.io/pstn-bridge";
    private static final String DOMAIN = "acme.sip.twilio.com";
    private static final String BRIDGE = "https://coldcalling-sip-9999-prod.twil.io/pstn-bridge";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CallRoutingService(settings, config -> twilioClient, config -> provisioner);
    }

    private void stubTwilioConfigured() {
        when(settings.getTwilioAccountSid()).thenReturn("AC0123456789");
        when(settings.getTwilioAuthToken()).thenReturn("tok");
        when(settings.getSipDomain()).thenReturn(DOMAIN);
    }

    // ── load ───────────────────────────────────────────────────────────────────

    @Test
    void load_defaultsToNone_whenUnset() {
        when(settings.getCallRoutingProvider()).thenReturn("twilio");
        when(settings.getCallRoutingMode()).thenReturn("none");
        when(settings.getCallRoutingVoiceUrl()).thenReturn("");
        when(settings.getCallRoutingCallerIdFallback()).thenReturn("");

        final CallRoutingConfig config = service.load();

        assertThat(config.mode()).isEqualTo(CallRoutingMode.NONE);
        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    void load_returnsManual_whenStored() {
        when(settings.getCallRoutingProvider()).thenReturn("twilio");
        when(settings.getCallRoutingMode()).thenReturn("manual");
        when(settings.getCallRoutingVoiceUrl()).thenReturn(URL);
        when(settings.getCallRoutingCallerIdFallback()).thenReturn("+12025550100");

        final CallRoutingConfig config = service.load();

        assertThat(config.mode()).isEqualTo(CallRoutingMode.MANUAL);
        assertThat(config.voiceUrl()).isEqualTo(URL);
        assertThat(config.callerIdFallback()).isEqualTo("+12025550100");
    }

    @Test
    void load_fallsBackToNone_whenModeUnparseable() {
        when(settings.getCallRoutingProvider()).thenReturn("twilio");
        when(settings.getCallRoutingMode()).thenReturn("garbage");
        when(settings.getCallRoutingVoiceUrl()).thenReturn("");
        when(settings.getCallRoutingCallerIdFallback()).thenReturn("");

        assertThat(service.load().mode()).isEqualTo(CallRoutingMode.NONE);
    }

    @Test
    void load_fallsBackToNone_whenStoredStateInvalid() {
        // mode=manual but no voiceUrl — would fail record validation
        when(settings.getCallRoutingProvider()).thenReturn("twilio");
        when(settings.getCallRoutingMode()).thenReturn("manual");
        when(settings.getCallRoutingVoiceUrl()).thenReturn("");
        when(settings.getCallRoutingCallerIdFallback()).thenReturn("");

        final CallRoutingConfig config = service.load();

        assertThat(config.mode()).isEqualTo(CallRoutingMode.NONE);
        assertThat(config.providerId()).isEqualTo("twilio");
    }

    // ── save ───────────────────────────────────────────────────────────────────

    @Test
    void save_writesAllPrimitives_lowercasingMode() {
        service.save(new CallRoutingConfig("twilio", CallRoutingMode.AUTO, URL, "+12025550100"));

        verify(settings).setCallRoutingProvider("twilio");
        verify(settings).setCallRoutingMode("auto");
        verify(settings).setCallRoutingVoiceUrl(URL);
        verify(settings).setCallRoutingCallerIdFallback("+12025550100");
    }

    // ── applyManual ──────────────────────────────────────────────────────────────

    @Test
    void applyManual_twilio_appliesToDomain_andPersists() {
        stubTwilioConfigured();
        when(twilioClient.setSipDomainVoiceUrl(DOMAIN, URL)).thenReturn(Result.ok(null));

        final Result<CallRoutingConfig> result = service.applyManual("twilio", URL, "+12025550100");

        assertThat(result.isOk()).isTrue();
        verify(twilioClient).setSipDomainVoiceUrl(DOMAIN, URL);
        verify(settings).setCallRoutingMode("manual");
        verify(settings).setCallRoutingVoiceUrl(URL);
    }

    @Test
    void applyManual_twilio_doesNotPersist_whenApiFails() {
        stubTwilioConfigured();
        when(twilioClient.setSipDomainVoiceUrl(DOMAIN, URL)).thenReturn(Result.err("domain not found"));

        final Result<CallRoutingConfig> result = service.applyManual("twilio", URL, "");

        assertThat(result.isErr()).isTrue();
        verify(settings, never()).setCallRoutingMode(any());
    }

    @Test
    void applyManual_invalidUrl_returnsErr_withoutNetwork() {
        final Result<CallRoutingConfig> result = service.applyManual("twilio", "http://insecure", "");

        assertThat(result.isErr()).isTrue();
        verify(twilioClient, never()).setSipDomainVoiceUrl(any(), any());
    }

    @Test
    void applyManual_twilioNotConfigured_returnsErr() {
        when(settings.getTwilioAccountSid()).thenReturn("");
        when(settings.getTwilioAuthToken()).thenReturn("");

        final Result<CallRoutingConfig> result = service.applyManual("twilio", URL, "");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<CallRoutingConfig>) result).message()).contains("Twilio isn't configured");
        verify(twilioClient, never()).setSipDomainVoiceUrl(any(), any());
    }

    @Test
    void applyManual_nonTwilioProvider_storesWithoutApiCall() {
        final Result<CallRoutingConfig> result = service.applyManual("vonage", URL, "");

        assertThat(result.isOk()).isTrue();
        verify(twilioClient, never()).setSipDomainVoiceUrl(any(), any());
        verify(settings).setCallRoutingProvider("vonage");
        verify(settings).setCallRoutingMode("manual");
    }

    // ── autoConfigure ────────────────────────────────────────────────────────────

    @Test
    void autoConfigure_twilio_provisionsBridge_appliesToDomain_andPersists() {
        stubTwilioConfigured();
        when(settings.getCallRoutingCallerIdFallback()).thenReturn("");
        when(provisioner.provisionBridge()).thenReturn(Result.ok(BRIDGE));
        when(twilioClient.setSipDomainVoiceUrl(DOMAIN, BRIDGE)).thenReturn(Result.ok(null));

        final Result<CallRoutingConfig> result = service.autoConfigure("twilio");

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<CallRoutingConfig>) result).value().voiceUrl()).isEqualTo(BRIDGE);
        verify(provisioner).provisionBridge();
        verify(twilioClient).setSipDomainVoiceUrl(DOMAIN, BRIDGE);
        verify(settings).setCallRoutingMode("auto");
        verify(settings).setCallRoutingVoiceUrl(BRIDGE);
    }

    @Test
    void autoConfigure_twilio_returnsErr_whenProvisioningFails_withoutPersist() {
        stubTwilioConfigured();
        when(provisioner.provisionBridge()).thenReturn(Result.err("Twilio Serverless build failed."));

        final Result<CallRoutingConfig> result = service.autoConfigure("twilio");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<CallRoutingConfig>) result).message()).contains("build failed");
        verify(twilioClient, never()).setSipDomainVoiceUrl(any(), any());
        verify(settings, never()).setCallRoutingMode(any());
    }

    @Test
    void autoConfigure_twilioNotConfigured_returnsErr_withoutProvisioning() {
        when(settings.getTwilioAccountSid()).thenReturn("");
        when(settings.getTwilioAuthToken()).thenReturn("");

        final Result<CallRoutingConfig> result = service.autoConfigure("twilio");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<CallRoutingConfig>) result).message()).contains("Twilio isn't configured");
        verify(provisioner, never()).provisionBridge();
        verify(twilioClient, never()).setSipDomainVoiceUrl(any(), any());
    }

    @Test
    void autoConfigure_nonTwilio_returnsErr_withoutProvisioningOrPersist() {
        final Result<CallRoutingConfig> result = service.autoConfigure("vonage");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<CallRoutingConfig>) result).message()).contains("isn't available");
        verify(provisioner, never()).provisionBridge();
        verify(twilioClient, never()).setSipDomainVoiceUrl(any(), any());
        verify(settings, never()).setCallRoutingMode(any());
    }

    // ── capabilities ─────────────────────────────────────────────────────────────

    @Test
    void supportsAutoRouting_trueOnlyForTwilio() {
        assertThat(service.supportsAutoRouting("twilio")).isTrue();
        assertThat(service.supportsAutoRouting("vonage")).isFalse();
    }

    @Test
    void currentVoiceUrl_twilio_delegatesToClient() {
        stubTwilioConfigured();
        when(twilioClient.readSipDomainVoiceUrl(DOMAIN)).thenReturn(Result.ok(Optional.of(URL)));

        final Result<Optional<String>> result = service.currentVoiceUrl("twilio");

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<Optional<String>>) result).value()).contains(URL);
    }

    @Test
    void currentVoiceUrl_nonTwilio_returnsErr() {
        assertThat(service.currentVoiceUrl("vonage").isErr()).isTrue();
    }
}
