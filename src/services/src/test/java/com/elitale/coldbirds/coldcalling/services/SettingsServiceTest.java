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
    void getTelnyxApiKey_returnsEmpty_whenNotSet() {
        when(repo.get(SettingsService.KEY_TELNYX_API_KEY)).thenReturn(Optional.empty());
        assertThat(service.getTelnyxApiKey()).isEmpty();
    }

    @Test
    void setTelnyxApiKey_delegates_toRepo() {
        service.setTelnyxApiKey("tk_abc123");
        verify(repo).set(SettingsService.KEY_TELNYX_API_KEY, "tk_abc123");
    }

    @Test
    void getSipDomain_returnsDefault_whenNotSet() {
        when(repo.get(SettingsService.KEY_SIP_DOMAIN)).thenReturn(Optional.empty());
        assertThat(service.getSipDomain()).isEqualTo("sip.telnyx.com");
    }

    @Test
    void getSipProxy_returnsDefault_whenNotSet() {
        when(repo.get(SettingsService.KEY_SIP_PROXY)).thenReturn(Optional.empty());
        assertThat(service.getSipProxy()).isEqualTo("sip.telnyx.com");
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
}
