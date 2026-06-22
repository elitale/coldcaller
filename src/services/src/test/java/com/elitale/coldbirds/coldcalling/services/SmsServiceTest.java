package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmsServiceTest {

    @Mock TwilioClient          twilio;
    @Mock SmsRepository         smsRepo;
    @Mock PhoneNumberRepository phoneNumberRepo;
    @Mock SettingsService       settings;

    SmsService service;

    private static final PhoneNumber FROM = new PhoneNumber("+12025551001");
    private static final PhoneNumber TO   = new PhoneNumber("+12025551002");
    private static final PhoneNumberId FROM_ID = new PhoneNumberId(1L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SmsService(twilio, smsRepo, phoneNumberRepo, settings);
    }

    @Test
    void send_delegatesToTwilioClient() {
        stubOwnedNumber(FROM, FROM_ID);
        when(twilio.sendSms(FROM, TO, "Hello")).thenReturn(Result.ok("msg-uuid-1"));
        when(smsRepo.save(any())).thenReturn(Result.err("stub"));

        service.send(FROM, TO, "Hello");

        verify(twilio).sendSms(FROM, TO, "Hello");
    }

    @Test
    void send_unknownFromNumber_doesNotDelegate() {
        when(phoneNumberRepo.findByNumber(FROM)).thenReturn(Optional.empty());

        service.send(FROM, TO, "Hello");

        verify(twilio, never()).sendSms(any(), any(), any());
    }

    @Test
    void send_twilioFailure_doesNotPersist() {
        stubOwnedNumber(FROM, FROM_ID);
        when(twilio.sendSms(FROM, TO, "Hello")).thenReturn(Result.err("api error"));

        service.send(FROM, TO, "Hello");

        verify(smsRepo, never()).save(any());
    }

    @Test
    void pollInbound_persistsNewMessages_andAdvancesWatermark() {
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        Instant sentAt = Instant.parse("2024-01-02T10:00:00Z");
        when(settings.getSmsLastPolledAt()).thenReturn(since);
        DomainEvent.IncomingSms event = new DomainEvent.IncomingSms(TO, FROM, "reply", sentAt);
        when(twilio.fetchInboundSince(since)).thenReturn(Result.ok(List.of(event)));
        stubOwnedNumber(FROM, FROM_ID);   // inbound 'to' our owned number FROM
        when(smsRepo.save(any())).thenReturn(Result.err("stub"));

        List<DomainEvent.IncomingSms> result = service.pollInbound();

        assertThat(result).containsExactly(event);
        verify(smsRepo).save(any());
        verify(settings).setSmsLastPolledAt(sentAt);
    }

    @Test
    void refreshInbound_returnsCountOfNewMessages() {
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        Instant sentAt = Instant.parse("2024-01-02T10:00:00Z");
        when(settings.getSmsLastPolledAt()).thenReturn(since);
        DomainEvent.IncomingSms event = new DomainEvent.IncomingSms(TO, FROM, "reply", sentAt);
        when(twilio.fetchInboundSince(since)).thenReturn(Result.ok(List.of(event)));
        stubOwnedNumber(FROM, FROM_ID);
        when(smsRepo.save(any())).thenReturn(Result.err("stub"));

        int count = service.refreshInbound();

        assertThat(count).isEqualTo(1);
        verify(settings).setSmsLastPolledAt(sentAt);
    }

    @Test
    void pollInbound_apiError_returnsEmpty_andDoesNotAdvanceWatermark() {
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        when(settings.getSmsLastPolledAt()).thenReturn(since);
        when(twilio.fetchInboundSince(since)).thenReturn(Result.err("api down"));

        List<DomainEvent.IncomingSms> result = service.pollInbound();

        assertThat(result).isEmpty();
        verify(smsRepo, never()).save(any());
        verify(settings, never()).setSmsLastPolledAt(any());
    }

    @Test
    void stopReceiving_whenNotPolling_isNoOp() {
        service.stopReceiving();  // must not throw
    }

    private void stubOwnedNumber(PhoneNumber number, PhoneNumberId id) {
        var owned = new com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber(
                id, number, Optional.empty(),
                new AreaCode("202"), "twilio", new NumberReputation.Clean(),
                0, true,
                java.time.Instant.now(), java.time.Instant.now()
        );
        when(phoneNumberRepo.findByNumber(number)).thenReturn(Optional.of(owned));
    }
}
