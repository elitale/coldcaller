package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioConfig;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingServiceTest {

    @Mock private SettingsService settings;
    @Mock private PhoneNumberService phoneNumbers;
    @Mock private SipTester sipTester;
    @Mock private TwilioClient twilioClient;

    private OnboardingService service;

    private static final SipCredentials SIP =
            new SipCredentials("user", "pass", "sip.twilio.com", "sip.twilio.com", 5060);
    private static final TwilioNumberData NUMBER =
            new TwilioNumberData("PN1", "+12025551001", "in-use");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        final Function<TwilioConfig, TwilioClient> factory = config -> twilioClient;
        service = new OnboardingService(settings, phoneNumbers, sipTester, factory);
    }

    @Test
    void testTwilio_blankCredentials_returnsErr() {
        final Result<List<TwilioNumberData>> result = service.testTwilio("", "");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void testTwilio_delegatesToClient_whenCredentialsPresent() {
        when(twilioClient.listPhoneNumbers()).thenReturn(Result.ok(List.of(NUMBER)));

        final Result<List<TwilioNumberData>> result = service.testTwilio("AC123", "tok");

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<List<TwilioNumberData>>) result).value()).containsExactly(NUMBER);
    }

    @Test
    void testSip_delegatesToSipTester() {
        final CompletableFuture<Result<Void>> fut = CompletableFuture.completedFuture(Result.ok(null));
        when(sipTester.test(SIP)).thenReturn(fut);

        assertThat(service.testSip(SIP)).isSameAs(fut);
    }

    @Test
    void autoConfigureSip_blankCredentials_returnsErr() {
        final Result<SipCredentials> result = service.autoConfigureSip("", "");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void autoConfigureSip_mapsProvisioningToCredentials() {
        when(twilioClient.autoProvisionSip())
                .thenReturn(Result.ok(new SipProvisioning("acme.sip.twilio.com", "user42", "Pa55word12345678")));

        final Result<SipCredentials> result = service.autoConfigureSip("AC123", "tok");

        assertThat(result).isInstanceOf(Result.Ok.class);
        final SipCredentials creds = ((Result.Ok<SipCredentials>) result).value();
        assertThat(creds.username()).isEqualTo("user42");
        assertThat(creds.password()).isEqualTo("Pa55word12345678");
        assertThat(creds.domain()).isEqualTo("acme.sip.twilio.com");
        assertThat(creds.proxyHost()).isEqualTo("acme.sip.twilio.com");
        assertThat(creds.proxyPort()).isEqualTo(5060);
    }

    @Test
    void autoConfigureSip_propagatesProvisioningError() {
        when(twilioClient.autoProvisionSip()).thenReturn(Result.err("Twilio SIP setup failed"));

        final Result<SipCredentials> result = service.autoConfigureSip("AC123", "tok");

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<SipCredentials>) result).message()).contains("Twilio SIP setup failed");
    }

    @Test
    void completeOnboarding_persistsEverything_andSetsFlagLast() {
        when(phoneNumbers.saveSelected(any())).thenReturn(Result.ok(1));

        final OnboardingResult result =
                new OnboardingResult("AC123", "tok", SIP, List.of(NUMBER));
        final Result<Integer> outcome = service.completeOnboarding(result);

        assertThat(outcome).isInstanceOf(Result.Ok.class);
        verify(settings).setTwilioAccountSid("AC123");
        verify(settings).setTwilioAuthToken("tok");
        verify(settings).setSipUsername("user");
        verify(settings).setSipProxyPort(5060);
        verify(phoneNumbers).saveSelected(List.of(NUMBER));
        verify(phoneNumbers).setDefault(new PhoneNumber("+12025551001"));
        verify(settings).setOnboardingComplete(true);
    }

    @Test
    void completeOnboarding_doesNotSetFlag_whenSaveFails() {
        when(phoneNumbers.saveSelected(any())).thenReturn(Result.err("db error"));

        final OnboardingResult result =
                new OnboardingResult("AC123", "tok", SIP, List.of(NUMBER));
        final Result<Integer> outcome = service.completeOnboarding(result);

        assertThat(outcome).isInstanceOf(Result.Err.class);
        verify(settings, never()).setOnboardingComplete(true);
    }

    @Test
    void isOnboardingComplete_delegatesToSettings() {
        when(settings.isOnboardingComplete()).thenReturn(true);
        assertThat(service.isOnboardingComplete()).isTrue();
    }

    @Test
    void loadDraft_readsAllValuesFromSettings() {
        when(settings.getTwilioAccountSid()).thenReturn("AC123");
        when(settings.getTwilioAuthToken()).thenReturn("tok");
        when(settings.getSipUsername()).thenReturn("user");
        when(settings.getSipPassword()).thenReturn("pass");
        when(settings.getSipDomain()).thenReturn("sip.twilio.com");
        when(settings.getSipProxy()).thenReturn("sip.twilio.com");
        when(settings.getSipProxyPort()).thenReturn(5060);

        final OnboardingDraft draft = service.loadDraft();

        assertThat(draft.accountSid()).isEqualTo("AC123");
        assertThat(draft.authToken()).isEqualTo("tok");
        assertThat(draft.sipUsername()).isEqualTo("user");
        assertThat(draft.sipProxyPort()).isEqualTo(5060);
        assertThat(draft.hasSip()).isTrue();
    }

    @Test
    void saveTwilioDraft_persistsTrimmedCredentials_withoutSettingFlag() {
        service.saveTwilioDraft("  AC123  ", "  tok  ");

        verify(settings).setTwilioAccountSid("AC123");
        verify(settings).setTwilioAuthToken("tok");
        verify(settings, never()).setOnboardingComplete(true);
    }

    @Test
    void saveSipDraft_persistsAllSipFields_withoutSettingFlag() {
        service.saveSipDraft(SIP);

        verify(settings).setSipUsername("user");
        verify(settings).setSipPassword("pass");
        verify(settings).setSipDomain("sip.twilio.com");
        verify(settings).setSipProxy("sip.twilio.com");
        verify(settings).setSipProxyPort(5060);
        verify(settings, never()).setOnboardingComplete(true);
    }
}
