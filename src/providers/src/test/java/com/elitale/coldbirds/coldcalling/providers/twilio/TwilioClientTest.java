package com.elitale.coldbirds.coldcalling.providers.twilio;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.exception.ApiConnectionException;
import com.twilio.http.Request;
import com.twilio.http.Response;
import com.twilio.http.TwilioRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwilioClientTest {

    private static final String ACCOUNT_SID = "AC0123456789abcdef0123456789abcdef";
    private static final String AUTH_TOKEN  = "the-auth-token";
    private static final PhoneNumber FROM = new PhoneNumber("+15551234567");
    private static final PhoneNumber TO   = new PhoneNumber("+15559876543");

    private static final String SEND_SMS_SUCCESS = """
            {"sid":"SM0123456789abcdef","status":"queued","body":"Hello!"}
            """;

    private static final String API_ERROR = """
            {"code":21211,"message":"The 'To' number is not valid.","status":400}
            """;

    private static final String LIST_NUMBERS_SUCCESS = """
            {"incoming_phone_numbers":[
              {"sid":"PN1","phone_number":"+15551111111","status":"in-use"},
              {"sid":"PN2","phone_number":"+15552222222","status":"in-use"}
            ],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":1,
            "uri":"/2010-04-01/Accounts/AC.../IncomingPhoneNumbers.json"}
            """;

    private TwilioConfig     config;
    private TwilioRestClient restClient;
    private ObjectMapper     mapper;

    @BeforeEach
    void setUp() {
        config     = TwilioConfig.of(ACCOUNT_SID, AUTH_TOKEN);
        restClient = mock(TwilioRestClient.class);
        mapper     = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        when(restClient.getAccountSid()).thenReturn(ACCOUNT_SID);
        when(restClient.getObjectMapper()).thenReturn(mapper);
    }

    // ── sendSms ─────────────────────────────────────────────────────────────

    @Test
    void testSendSms_success_returnsOkWithMessageSid() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(SEND_SMS_SUCCESS, 201));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<String> result = client.sendSms(FROM, TO, "Hello!");

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<String>) result).value()).isEqualTo("SM0123456789abcdef");
    }

    @Test
    void testSendSms_apiError_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(API_ERROR, 400));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<String> result = client.sendSms(FROM, TO, "Hello!");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).contains("failed");
    }

    @Test
    void testSendSms_networkError_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenThrow(new ApiConnectionException("connection refused"));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<String> result = client.sendSms(FROM, TO, "Hello!");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).contains("connection refused");
    }

    @Test
    void testSendSms_postsToMessagesEndpoint() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(SEND_SMS_SUCCESS, 201));
        TwilioClient client = new TwilioClient(config, restClient);

        org.mockito.ArgumentCaptor<Request> captor =
                org.mockito.ArgumentCaptor.forClass(Request.class);

        client.sendSms(FROM, TO, "Hello!");

        org.mockito.Mockito.verify(restClient).request(captor.capture());
        assertThat(captor.getValue().getMethod().toString()).isEqualTo("POST");
        assertThat(captor.getValue().constructURL().toString()).contains("/Messages.json");
    }

    @Test
    void testSendSms_blankBody_returnsErr() {
        TwilioClient client = new TwilioClient(config, restClient);

        Result<String> result = client.sendSms(FROM, TO, "   ");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).contains("blank");
    }

    @Test
    void testSendSms_nullFrom_throws() {
        TwilioClient client = new TwilioClient(config, restClient);

        assertThatThrownBy(() -> client.sendSms(null, TO, "Hello!"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSendSms_notConfigured_returnsErrWithoutNetwork() {
        TwilioConfig blank = new TwilioConfig("", "", TwilioConfig.DEFAULT_BASE_URL);
        TwilioClient client = new TwilioClient(blank, restClient);

        Result<String> result = client.sendSms(FROM, TO, "Hello!");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).contains("not configured");
    }

    // ── listPhoneNumbers ─────────────────────────────────────────────────────

    @Test
    void testListPhoneNumbers_success_returnsList() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(LIST_NUMBERS_SUCCESS, 200));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<List<TwilioNumberData>> result = client.listPhoneNumbers();

        assertThat(result.isOk()).isTrue();
        List<TwilioNumberData> numbers = ((Result.Ok<List<TwilioNumberData>>) result).value();
        assertThat(numbers).hasSize(2);
        assertThat(numbers.get(0).phoneNumber()).isEqualTo("+15551111111");
        assertThat(numbers.get(1).phoneNumber()).isEqualTo("+15552222222");
    }

    @Test
    void testListPhoneNumbers_apiError_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(API_ERROR, 401));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<List<TwilioNumberData>> result = client.listPhoneNumbers();

        assertThat(result.isErr()).isTrue();
    }

    @Test
    void testListPhoneNumbers_notConfigured_returnsErr() {
        TwilioConfig blank = new TwilioConfig("", "", TwilioConfig.DEFAULT_BASE_URL);
        TwilioClient client = new TwilioClient(blank, restClient);

        Result<List<TwilioNumberData>> result = client.listPhoneNumbers();

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<List<TwilioNumberData>>) result).message()).contains("not configured");
    }

    // ── TwilioConfig ─────────────────────────────────────────────────────────

    @Test
    void testConfig_of_setsDefaultBaseUrl() {
        TwilioConfig cfg = TwilioConfig.of(ACCOUNT_SID, AUTH_TOKEN);
        assertThat(cfg.baseUrl()).isEqualTo(TwilioConfig.DEFAULT_BASE_URL);
        assertThat(cfg.accountSid()).isEqualTo(ACCOUNT_SID);
        assertThat(cfg.authToken()).isEqualTo(AUTH_TOKEN);
    }

    @Test
    void testConfig_nullAccountSid_throws() {
        assertThatThrownBy(() -> new TwilioConfig(null, AUTH_TOKEN, TwilioConfig.DEFAULT_BASE_URL))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testConfig_blankCredentials_isNotConfigured() {
        TwilioConfig cfg = new TwilioConfig("  ", "  ", TwilioConfig.DEFAULT_BASE_URL);
        assertThat(cfg.isConfigured()).isFalse();
    }

    @Test
    void testConfig_nullAuthToken_throws() {
        assertThatThrownBy(() -> new TwilioConfig(ACCOUNT_SID, null, TwilioConfig.DEFAULT_BASE_URL))
                .isInstanceOf(NullPointerException.class);
    }
}
