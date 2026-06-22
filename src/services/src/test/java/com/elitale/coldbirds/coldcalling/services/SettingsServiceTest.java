package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsServiceTest {

    @Mock private SettingsRepository repo;

    private SettingsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SettingsService(repo);
    }

    @Test
    void constructor_rejectsNullRepo() {
        assertThatThrownBy(() -> new SettingsService(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getTwilioAccountSid_returnsEmpty_whenNotSet() {
        when(repo.get(SettingsService.KEY_TWILIO_ACCOUNT_SID)).thenReturn(Optional.empty());
        assertThat(service.getTwilioAccountSid()).isEmpty();
    }

    @Test
    void setTwilioAccountSid_delegates_toRepo() {
        service.setTwilioAccountSid("AC0123456789abcdef");
        verify(repo).set(SettingsService.KEY_TWILIO_ACCOUNT_SID, "AC0123456789abcdef");
    }

    @Test
    void setTwilioAuthToken_delegates_toRepo() {
        service.setTwilioAuthToken("tok_abc123");
        verify(repo).set(SettingsService.KEY_TWILIO_AUTH_TOKEN, "tok_abc123");
    }

    @Test
    void getSipDomain_returnsDefault_whenNotSet() {
        when(repo.get(SettingsService.KEY_SIP_DOMAIN)).thenReturn(Optional.empty());
        assertThat(service.getSipDomain()).isEqualTo("sip.twilio.com");
    }

    @Test
    void getSipProxy_returnsDefault_whenNotSet() {
        when(repo.get(SettingsService.KEY_SIP_PROXY)).thenReturn(Optional.empty());
        assertThat(service.getSipProxy()).isEqualTo("sip.twilio.com");
    }

    @Test
    void getSipProxyPort_returnsDefault_whenNotSet() {
        when(repo.get(SettingsService.KEY_SIP_PROXY_PORT)).thenReturn(Optional.empty());
        assertThat(service.getSipProxyPort()).isEqualTo(5060);
    }

    @Test
    void getSipProxyPort_returnsDefault_whenValueIsNotParseable() {
        when(repo.get(SettingsService.KEY_SIP_PROXY_PORT)).thenReturn(Optional.of("notanumber"));
        assertThat(service.getSipProxyPort()).isEqualTo(5060);
    }

    @Test
    void isOnboardingComplete_defaultsFalse_whenNotSet() {
        when(repo.get(SettingsService.KEY_ONBOARDING_COMPLETED)).thenReturn(Optional.empty());
        assertThat(service.isOnboardingComplete()).isFalse();
    }

    @Test
    void onboardingComplete_roundTrips() {
        service.setOnboardingComplete(true);
        verify(repo).set(SettingsService.KEY_ONBOARDING_COMPLETED, "true");

        when(repo.get(SettingsService.KEY_ONBOARDING_COMPLETED)).thenReturn(Optional.of("true"));
        assertThat(service.isOnboardingComplete()).isTrue();
    }

    @Test
    void getJitterBufferMs_returnsDefault_whenNotSet() {
        when(repo.get(SettingsService.KEY_AUDIO_JITTER_BUFFER_MS)).thenReturn(Optional.empty());
        assertThat(service.getJitterBufferMs()).isEqualTo(40);
    }

    @Test
    void isVoicemailDropEnabled_returnsFalse_whenNotSet() {
        when(repo.get(SettingsService.KEY_DIALER_VOICEMAIL_DROP)).thenReturn(Optional.empty());
        assertThat(service.isVoicemailDropEnabled()).isFalse();
    }

    @Test
    void isVoicemailDropEnabled_returnsTrue_whenStoredAsTrue() {
        when(repo.get(SettingsService.KEY_DIALER_VOICEMAIL_DROP)).thenReturn(Optional.of("true"));
        assertThat(service.isVoicemailDropEnabled()).isTrue();
    }

    @Test
    void setVoicemailDropEnabled_delegates_toRepo() {
        service.setVoicemailDropEnabled(true);
        verify(repo).set(SettingsService.KEY_DIALER_VOICEMAIL_DROP, "true");
    }

    @Test
    void getVoicemailGreetingPath_returnsBlank_whenNotSet() {
        when(repo.get(SettingsService.KEY_DIALER_VOICEMAIL_GREETING)).thenReturn(Optional.empty());
        assertThat(service.getVoicemailGreetingPath()).isEmpty();
    }

    @Test
    void setVoicemailGreetingPath_delegates_toRepo() {
        service.setVoicemailGreetingPath("/tmp/greeting.wav");
        verify(repo).set(SettingsService.KEY_DIALER_VOICEMAIL_GREETING, "/tmp/greeting.wav");
    }

    @Test
    void isReduceMotion_returnsFalse_whenNotSet() {
        when(repo.get(SettingsService.KEY_UI_REDUCE_MOTION)).thenReturn(Optional.empty());
        assertThat(service.isReduceMotion()).isFalse();
    }

    @Test
    void isReduceMotion_returnsTrue_whenStoredAsTrue() {
        when(repo.get(SettingsService.KEY_UI_REDUCE_MOTION)).thenReturn(Optional.of("true"));
        assertThat(service.isReduceMotion()).isTrue();
    }

    @Test
    void setReduceMotion_delegates_toRepo() {
        service.setReduceMotion(true);
        verify(repo).set(SettingsService.KEY_UI_REDUCE_MOTION, "true");
    }

    @Test
    void getCallRoutingProvider_defaultsTwilio_whenNotSet() {
        when(repo.get(SettingsService.KEY_CALL_ROUTING_PROVIDER)).thenReturn(Optional.empty());
        assertThat(service.getCallRoutingProvider()).isEqualTo("twilio");
    }

    @Test
    void getCallRoutingMode_defaultsNone_whenNotSet() {
        when(repo.get(SettingsService.KEY_CALL_ROUTING_MODE)).thenReturn(Optional.empty());
        assertThat(service.getCallRoutingMode()).isEqualTo("none");
    }

    @Test
    void getCallRoutingVoiceUrl_defaultsEmpty_whenNotSet() {
        when(repo.get(SettingsService.KEY_CALL_ROUTING_VOICE_URL)).thenReturn(Optional.empty());
        assertThat(service.getCallRoutingVoiceUrl()).isEmpty();
    }

    @Test
    void getCallRoutingCallerIdFallback_defaultsEmpty_whenNotSet() {
        when(repo.get(SettingsService.KEY_CALL_ROUTING_CALLER_ID)).thenReturn(Optional.empty());
        assertThat(service.getCallRoutingCallerIdFallback()).isEmpty();
    }

    @Test
    void setCallRoutingFields_delegate_toRepo() {
        service.setCallRoutingProvider("twilio");
        service.setCallRoutingMode("auto");
        service.setCallRoutingVoiceUrl("https://example.twil.io/pstn-bridge");
        service.setCallRoutingCallerIdFallback("+12025550100");

        verify(repo).set(SettingsService.KEY_CALL_ROUTING_PROVIDER, "twilio");
        verify(repo).set(SettingsService.KEY_CALL_ROUTING_MODE, "auto");
        verify(repo).set(SettingsService.KEY_CALL_ROUTING_VOICE_URL, "https://example.twil.io/pstn-bridge");
        verify(repo).set(SettingsService.KEY_CALL_ROUTING_CALLER_ID, "+12025550100");
    }
}
