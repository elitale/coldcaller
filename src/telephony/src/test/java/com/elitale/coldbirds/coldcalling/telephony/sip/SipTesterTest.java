package com.elitale.coldbirds.coldcalling.telephony.sip;

import com.elitale.coldbirds.coldcalling.domain.value.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SipTesterTest {

    private static final SipCredentials CREDS =
            new SipCredentials("user", "pass", "sip.twilio.com", "sip.twilio.com", 5060);

    @Test
    void test_completesOk_whenRegistrationSucceeds() throws Exception {
        final SipTester tester = new SipTester((creds, listener) -> {
            listener.onRegistered();
            return () -> { };
        });

        final Result<Void> result = tester.test(CREDS, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void test_completesErr_whenAuthFails() throws Exception {
        final SipTester tester = new SipTester((creds, listener) -> {
            listener.onRegistrationFailed(401, "Unauthorized");
            return () -> { };
        });

        final Result<Void> result = tester.test(CREDS, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Void>) result).message()).contains("Authentication failed");
    }

    @Test
    void test_completesErr_onTimeout_whenNoResponse() throws Exception {
        final SipTester tester = new SipTester((creds, listener) -> () -> { });

        final Result<Void> result = tester.test(CREDS, Duration.ofMillis(100)).get(2, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Void>) result).message()).contains("No response");
    }

    @Test
    void test_closesProbeHandle_afterCompletion() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final SipTester tester = new SipTester((creds, listener) -> {
            listener.onRegistered();
            return () -> closed.set(true);
        });

        tester.test(CREDS, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);

        assertThat(closed).isTrue();
    }

    @Test
    void test_completesErr_whenProbeThrows() throws Exception {
        final SipTester tester = new SipTester((creds, listener) -> {
            throw new IllegalStateException("boom");
        });

        final Result<Void> result = tester.test(CREDS, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Void>) result).message()).contains("Could not start SIP test");
    }
}
