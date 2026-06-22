package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.sms.SmsRelayClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmsServiceTest {

    @Mock TwilioClient          twilio;
    @Mock SmsRelayClient        relay;
    @Mock SmsRepository         smsRepo;
    @Mock PhoneNumberRepository phoneNumberRepo;

    SmsService service;

    private static final PhoneNumber FROM = new PhoneNumber("+12025551001");
    private static final PhoneNumber TO   = new PhoneNumber("+12025551002");
    private static final PhoneNumberId FROM_ID = new PhoneNumberId(1L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SmsService(twilio, relay, smsRepo, phoneNumberRepo);
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
    void startReceiving_connectsRelay() {
        service.startReceiving(sms -> {});
        verify(relay).connect(any());
    }

    @Test
    void stopReceiving_disconnectsRelay() {
        service.stopReceiving();
        verify(relay).disconnect();
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
