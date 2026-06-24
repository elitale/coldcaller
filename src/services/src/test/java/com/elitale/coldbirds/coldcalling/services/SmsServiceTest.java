package com.elitale.coldbirds.coldcalling.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.domain.value.SmsId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository.NewSmsMessage;

class SmsServiceTest {

    @Mock TwilioClient          twilio;
    @Mock SmsRepository         smsRepo;
    @Mock PhoneNumberRepository phoneNumberRepo;
    @Mock LeadRepository        leadRepo;
    @Mock SettingsService       settings;

    SmsService service;

    private static final PhoneNumber FROM = new PhoneNumber("+12025551001");
    private static final PhoneNumber TO   = new PhoneNumber("+12025551002");
    private static final PhoneNumberId FROM_ID = new PhoneNumberId(1L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SmsService(twilio, smsRepo, phoneNumberRepo, leadRepo, settings);
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
    void send_twilioFailure_persistsFailedStatus() {
        stubOwnedNumber(FROM, FROM_ID);
        when(twilio.sendSms(FROM, TO, "Hello")).thenReturn(Result.err("api error"));
        when(smsRepo.save(any())).thenReturn(Result.err("stub"));

        Result<SmsId> result = service.send(FROM, TO, "Hello");

        ArgumentCaptor<NewSmsMessage> captor = ArgumentCaptor.forClass(NewSmsMessage.class);
        verify(smsRepo).save(captor.capture());
        assertThat(captor.getValue().status()).isInstanceOf(SmsStatus.Failed.class);
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void send_toDoNotContact_blocksAndDoesNotDelegate() {
        stubOwnedNumber(FROM, FROM_ID);
        when(leadRepo.findByPhone(TO)).thenReturn(Optional.of(leadWithDnc(TO, true)));

        Result<SmsId> result = service.send(FROM, TO, "Hello");

        assertThat(result).isInstanceOf(Result.Err.class);
        verify(twilio, never()).sendSms(any(), any(), any());
        verify(smsRepo, never()).save(any());
    }

    @Test
    void pollInbound_stopKeyword_marksLeadOptedOut() {
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        Instant sentAt = Instant.parse("2024-01-02T10:00:00Z");
        when(settings.getSmsLastPolledAt()).thenReturn(since);
        // inbound STOP from the lead (from=TO) to our owned number FROM
        DomainEvent.IncomingSms event = new DomainEvent.IncomingSms(TO, FROM, "STOP", sentAt);
        when(twilio.fetchInboundSince(since)).thenReturn(Result.ok(List.of(event)));
        stubOwnedNumber(FROM, FROM_ID);
        Lead lead = leadWithDnc(TO, false);
        when(leadRepo.findByPhone(TO)).thenReturn(Optional.of(lead));
        when(smsRepo.save(any())).thenReturn(Result.err("stub"));

        service.pollInbound();

        verify(leadRepo).bulkSetDnc(List.of(lead.id()), true);
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

    @Test
    void threadNumber_returnsMostRecentMessagesNumber() {
        PhoneNumberId n1 = new PhoneNumberId(1L);
        PhoneNumberId n2 = new PhoneNumberId(2L);
        when(smsRepo.findByRemoteNumber(TO)).thenReturn(List.of(
                msg(n1, Instant.parse("2024-01-01T00:00:00Z")),
                msg(n2, Instant.parse("2024-01-02T00:00:00Z"))));
        assertThat(service.threadNumber(TO)).contains(n2);
    }

    @Test
    void threadNumber_emptyWhenNoThread() {
        when(smsRepo.findByRemoteNumber(TO)).thenReturn(List.of());
        assertThat(service.threadNumber(TO)).isEmpty();
    }

    private static com.elitale.coldbirds.coldcalling.domain.model.SmsMessage msg(PhoneNumberId on, Instant at) {
        return new com.elitale.coldbirds.coldcalling.domain.model.SmsMessage(
                new SmsId(1L), CallDirection.INBOUND, on, Optional.empty(),
                TO, "hi", new SmsStatus.Delivered(), at, at, at);
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

    private static Lead leadWithDnc(PhoneNumber phone, boolean dnc) {
        return new Lead(
                new LeadId(7L), Optional.of("Test"), Optional.empty(), phone,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), dnc, Map.of(), LeadStatus.NEW,
                Instant.now(), Instant.now());
    }
}
