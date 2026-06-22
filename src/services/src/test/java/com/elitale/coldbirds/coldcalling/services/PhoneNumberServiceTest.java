package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PhoneNumberServiceTest {

    @Mock PhoneNumberRepository repo;
    @Mock TwilioClient          twilio;
    @Mock SettingsRepository    settings;

    PhoneNumberService service;

    private static final PhoneNumber NUMBER = new PhoneNumber("+12025551001");
    private static final PhoneNumberId ID   = new PhoneNumberId(1L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PhoneNumberService(repo, twilio, settings);
    }

    @Test
    void listOwned_delegatesToRepo() {
        when(repo.findAllActive()).thenReturn(List.of());
        assertThat(service.listOwned()).isEmpty();
        verify(repo).findAllActive();
    }

    @Test
    void getDefault_returnsNumberFromSettings() {
        when(settings.get("default_number")).thenReturn(Optional.of(NUMBER.value()));
        when(repo.findByNumber(NUMBER)).thenReturn(Optional.of(stubOwned()));

        final Optional<OwnedNumber> result = service.getDefault();

        assertThat(result).isPresent();
        assertThat(result.get().number()).isEqualTo(NUMBER);
    }

    @Test
    void getDefault_emptyWhenSettingAbsent() {
        when(settings.get("default_number")).thenReturn(Optional.empty());
        assertThat(service.getDefault()).isEmpty();
    }

    @Test
    void fetchAndSync_savesNewNumbers() {
        when(twilio.listPhoneNumbers()).thenReturn(
                Result.ok(List.of(new TwilioNumberData("PN1", NUMBER.value(), "in-use")))
        );
        when(repo.findByNumber(NUMBER)).thenReturn(Optional.empty());
        when(repo.save(any())).thenReturn(Result.ok(stubOwned()));

        final Result<Integer> result = service.fetchAndSync();

        assertThat(result).isInstanceOf(Result.Ok.class);
        verify(repo).save(any());
    }

    @Test
    void fetchAndSync_skipsExistingNumbers() {
        when(twilio.listPhoneNumbers()).thenReturn(
                Result.ok(List.of(new TwilioNumberData("PN1", NUMBER.value(), "in-use")))
        );
        when(repo.findByNumber(NUMBER)).thenReturn(Optional.of(stubOwned()));

        service.fetchAndSync();

        verify(repo, never()).save(any());
    }

    @Test
    void fetchAndSync_twilioError_returnsErr() {
        when(twilio.listPhoneNumbers()).thenReturn(Result.err("api error"));
        final Result<Integer> result = service.fetchAndSync();
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void saveSelected_savesNewNumbers() {
        when(repo.findByNumber(NUMBER)).thenReturn(Optional.empty());
        when(repo.save(any())).thenReturn(Result.ok(stubOwned()));

        final Result<Integer> result = service.saveSelected(
                List.of(new TwilioNumberData("PN1", NUMBER.value(), "in-use"))
        );

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<Integer>) result).value()).isEqualTo(1);
        verify(repo).save(any());
    }

    @Test
    void saveSelected_skipsExistingNumbers() {
        when(repo.findByNumber(NUMBER)).thenReturn(Optional.of(stubOwned()));

        final Result<Integer> result = service.saveSelected(
                List.of(new TwilioNumberData("PN1", NUMBER.value(), "in-use"))
        );

        assertThat(((Result.Ok<Integer>) result).value()).isZero();
        verify(repo, never()).save(any());
    }

    @Test
    void saveSelected_skipsInvalidNumbers() {
        final Result<Integer> result = service.saveSelected(
                List.of(new TwilioNumberData("PN1", "not-e164", "in-use"))
        );

        assertThat(((Result.Ok<Integer>) result).value()).isZero();
        verify(repo, never()).save(any());
    }

    private OwnedNumber stubOwned() {
        return new OwnedNumber(
                ID, NUMBER, Optional.empty(),
                new AreaCode("202"), "twilio", new NumberReputation.Clean(),
                0, true, Instant.now(), Instant.now()
        );
    }
}
