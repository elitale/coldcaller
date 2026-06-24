package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsId;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RecentContactsTest {

    private static final PhoneNumber A = new PhoneNumber("+12025550001");
    private static final PhoneNumber B = new PhoneNumber("+12025550002");
    private static final PhoneNumber C = new PhoneNumber("+12025550003");
    private static final Instant T1 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2024-01-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2024-01-03T00:00:00Z");
    private static final Instant T5 = Instant.parse("2024-01-05T00:00:00Z");

    @Test
    void dedupesPerNumber_newestFirst_acrossCallsAndSms() {
        List<Call> calls = List.of(call(A, T1), call(B, T3));
        List<SmsMessage> sms = List.of(sms(A, T5), sms(C, T2));   // A's newest touch is the text at T5

        assertThat(RecentContacts.recent(calls, sms, 10)).containsExactly(A, B, C);
    }

    @Test
    void capLimitsCount() {
        List<Call> calls = List.of(call(A, T1), call(B, T3), call(C, T2));
        assertThat(RecentContacts.recent(calls, List.of(), 2)).containsExactly(B, C);
    }

    @Test
    void emptyInputs_emptyResult() {
        assertThat(RecentContacts.recent(List.of(), List.of(), 8)).isEmpty();
    }

    @Test
    void capZero_emptyResult() {
        assertThat(RecentContacts.recent(List.of(call(A, T1)), List.of(), 0)).isEmpty();
    }

    private static Call call(PhoneNumber remote, Instant at) {
        return new Call(
                new CallId(1L), CallDirection.OUTBOUND, new PhoneNumberId(1L), Optional.empty(),
                remote, Optional.empty(), at, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), at, at);
    }

    private static SmsMessage sms(PhoneNumber remote, Instant at) {
        return new SmsMessage(
                new SmsId(1L), CallDirection.INBOUND, new PhoneNumberId(1L), Optional.empty(),
                remote, "hi", new SmsStatus.Delivered(), at, at, at);
    }
}
