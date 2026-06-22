package com.elitale.coldbirds.coldcalling.providers.twilio;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    private static final String LIST_MESSAGES_SUCCESS = """
            {"messages":[
              {"sid":"SM1","from":"+15551112222","to":"+15559998888","body":"inbound hi",
               "direction":"inbound","date_sent":"Mon, 01 Jan 2024 12:00:00 +0000"},
              {"sid":"SM2","from":"+15559998888","to":"+15551112222","body":"outbound reply",
               "direction":"outbound-reply","date_sent":"Mon, 01 Jan 2024 12:01:00 +0000"},
              {"sid":"SM3","from":"+15551113333","to":"+15559998888","body":"too old",
               "direction":"inbound","date_sent":"Tue, 01 Jan 2019 12:00:00 +0000"}
            ],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":2,
            "uri":"/2010-04-01/Accounts/AC.../Messages.json"}
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

    // ── fetchInboundSince ────────────────────────────────────────────────────

    @Test
    void testFetchInboundSince_returnsOnlyInboundNewerThanSince() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(LIST_MESSAGES_SUCCESS, 200));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<List<DomainEvent.IncomingSms>> result =
                client.fetchInboundSince(Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(result.isOk()).isTrue();
        List<DomainEvent.IncomingSms> inbound = ((Result.Ok<List<DomainEvent.IncomingSms>>) result).value();
        assertThat(inbound).hasSize(1);                              // outbound + too-old excluded
        assertThat(inbound.get(0).from()).isEqualTo(new PhoneNumber("+15551112222"));
        assertThat(inbound.get(0).body()).isEqualTo("inbound hi");
    }

    @Test
    void testFetchInboundSince_apiError_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(API_ERROR, 401));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<List<DomainEvent.IncomingSms>> result =
                client.fetchInboundSince(Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(result.isErr()).isTrue();
    }

    @Test
    void testFetchInboundSince_notConfigured_returnsErr() {
        TwilioConfig blank = new TwilioConfig("", "", TwilioConfig.DEFAULT_BASE_URL);
        TwilioClient client = new TwilioClient(blank, restClient);

        Result<List<DomainEvent.IncomingSms>> result =
                client.fetchInboundSince(Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<List<DomainEvent.IncomingSms>>) result).message()).contains("not configured");
    }

    // ── autoProvisionSip ─────────────────────────────────────────────────────

    private static final String DOMAINS_EMPTY = """
            {"domains":[],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":0,
            "uri":"/2010-04-01/Accounts/AC.../SIP/Domains.json"}
            """;

    private static final String DOMAINS_WITH_REGISTRATION = """
            {"domains":[
              {"sid":"SD1","domain_name":"existing.sip.twilio.com","sip_registration":true,
               "friendly_name":"mine"}
            ],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":0,
            "uri":"/2010-04-01/Accounts/AC.../SIP/Domains.json"}
            """;

    private static final String DOMAIN_CREATED = """
            {"sid":"SD2","domain_name":"coldcalling-deadbeef.sip.twilio.com","sip_registration":true}
            """;

    private static final String CREDENTIAL_LISTS_EMPTY = """
            {"credential_lists":[],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":0,
            "uri":"/2010-04-01/Accounts/AC.../SIP/CredentialLists.json"}
            """;

    private static final String CREDENTIAL_LIST_CREATED = """
            {"sid":"CL1","friendly_name":"coldCalling"}
            """;

    private static final String CREDENTIAL_CREATED = """
            {"sid":"CR1","username":"coldcalling1234"}
            """;

    private static final String MAPPING_CREATED = """
            {"sid":"CLM1","friendly_name":"coldCalling"}
            """;

    /** Dispatch canned responses by request method + URL, mimicking the Twilio SIP endpoints. */
    private void stubSipDispatcher(final String domainsListJson, final String credListsJson) {
        when(restClient.request(any(Request.class))).thenAnswer(invocation -> {
            final Request req = invocation.getArgument(0);
            final String url = req.constructURL().toString();
            final String method = req.getMethod().toString();
            if (url.contains("CredentialListMappings.json")) {
                return new Response(MAPPING_CREATED, 201);
            }
            if (url.contains("/Credentials.json")) {
                return new Response(CREDENTIAL_CREATED, 201);
            }
            if (url.contains("/CredentialLists.json")) {
                return "POST".equals(method)
                        ? new Response(CREDENTIAL_LIST_CREATED, 201)
                        : new Response(credListsJson, 200);
            }
            if (url.contains("/Domains.json")) {
                return "POST".equals(method)
                        ? new Response(DOMAIN_CREATED, 201)
                        : new Response(domainsListJson, 200);
            }
            return new Response(API_ERROR, 404);
        });
    }

    @Test
    void testAutoProvisionSip_createsDomainAndCredential_whenNoneExist() {
        stubSipDispatcher(DOMAINS_EMPTY, CREDENTIAL_LISTS_EMPTY);
        TwilioClient client = new TwilioClient(config, restClient);

        Result<com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning> result =
                client.autoProvisionSip();

        assertThat(result.isOk()).isTrue();
        var prov = ((Result.Ok<com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning>) result).value();
        assertThat(prov.domainName()).isEqualTo("coldcalling-deadbeef.sip.twilio.com");
        assertThat(prov.username()).startsWith("coldcalling");
        assertThat(prov.password()).hasSize(16);
    }

    @Test
    void testAutoProvisionSip_reusesExistingRegistrationDomain() {
        stubSipDispatcher(DOMAINS_WITH_REGISTRATION, CREDENTIAL_LISTS_EMPTY);
        TwilioClient client = new TwilioClient(config, restClient);

        Result<com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning> result =
                client.autoProvisionSip();

        assertThat(result.isOk()).isTrue();
        var prov = ((Result.Ok<com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning>) result).value();
        assertThat(prov.domainName()).isEqualTo("existing.sip.twilio.com");
    }

    @Test
    void testAutoProvisionSip_apiError_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(API_ERROR, 401));
        TwilioClient client = new TwilioClient(config, restClient);

        assertThat(client.autoProvisionSip().isErr()).isTrue();
    }

    @Test
    void testAutoProvisionSip_notConfigured_returnsErr() {
        TwilioConfig blank = new TwilioConfig("", "", TwilioConfig.DEFAULT_BASE_URL);
        TwilioClient client = new TwilioClient(blank, restClient);

        Result<com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning> result =
                client.autoProvisionSip();

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<?>) result).message()).contains("not configured");
    }

    // ── SIP voice routing (PSTN bridge) ──────────────────────────────────────

    private static final String DOMAINS_ONE = """
            {"domains":[
              {"sid":"SD9","domain_name":"coldcalling-1234.sip.twilio.com","sip_registration":true,
               "voice_url":"https://old.example.com/in","voice_method":"POST"}
            ],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":0,
            "uri":"/2010-04-01/Accounts/AC.../SIP/Domains.json"}
            """;

    private static final String DOMAINS_NO_VOICE = """
            {"domains":[
              {"sid":"SD9","domain_name":"coldcalling-1234.sip.twilio.com","sip_registration":true}
            ],"next_page_uri":null,"page":0,"page_size":50,"start":0,"end":0,
            "uri":"/2010-04-01/Accounts/AC.../SIP/Domains.json"}
            """;

    private static final String DOMAIN_UPDATED = """
            {"sid":"SD9","domain_name":"coldcalling-1234.sip.twilio.com",
             "voice_url":"https://example.twil.io/pstn-bridge","voice_method":"POST"}
            """;

    /** Dispatch the reader (GET list) vs updater (POST to the SID resource) by URL shape. */
    private void stubDomainReadThenUpdate(final String listJson) {
        when(restClient.request(any(Request.class))).thenAnswer(invocation -> {
            final Request req = invocation.getArgument(0);
            return req.constructURL().toString().contains("/Domains/")
                    ? new Response(DOMAIN_UPDATED, 200)   // updater — path has the domain SID
                    : new Response(listJson, 200);        // reader — list endpoint
        });
    }

    @Test
    void testSetSipDomainVoiceUrl_success_returnsOk() {
        stubDomainReadThenUpdate(DOMAINS_ONE);
        TwilioClient client = new TwilioClient(config, restClient);

        Result<Void> result = client.setSipDomainVoiceUrl(
                "coldcalling-1234.sip.twilio.com", "https://example.twil.io/pstn-bridge");

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void testSetSipDomainVoiceUrl_postsVoiceUrlToDomainResource() {
        stubDomainReadThenUpdate(DOMAINS_ONE);
        TwilioClient client = new TwilioClient(config, restClient);

        org.mockito.ArgumentCaptor<Request> captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        client.setSipDomainVoiceUrl("coldcalling-1234.sip.twilio.com", "https://example.twil.io/pstn-bridge");

        org.mockito.Mockito.verify(restClient, org.mockito.Mockito.atLeastOnce()).request(captor.capture());
        Request update = captor.getAllValues().stream()
                .filter(r -> r.constructURL().toString().contains("/Domains/"))
                .findFirst().orElseThrow();
        assertThat(update.getMethod().toString()).isEqualTo("POST");
        assertThat(update.getPostParams().toString()).contains("https://example.twil.io/pstn-bridge");
    }

    @Test
    void testSetSipDomainVoiceUrl_domainNotFound_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(DOMAINS_EMPTY, 200));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<Void> result = client.setSipDomainVoiceUrl(
                "missing.sip.twilio.com", "https://example.twil.io/pstn-bridge");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<Void>) result).message()).contains("not found");
    }

    @Test
    void testSetSipDomainVoiceUrl_notConfigured_returnsErr() {
        TwilioConfig blank = new TwilioConfig("", "", TwilioConfig.DEFAULT_BASE_URL);
        TwilioClient client = new TwilioClient(blank, restClient);

        Result<Void> result = client.setSipDomainVoiceUrl(
                "x.sip.twilio.com", "https://example.twil.io/pstn-bridge");

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<Void>) result).message()).contains("not configured");
    }

    @Test
    void testReadSipDomainVoiceUrl_returnsUrl_whenSet() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(DOMAINS_ONE, 200));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<Optional<String>> result =
                client.readSipDomainVoiceUrl("coldcalling-1234.sip.twilio.com");

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<Optional<String>>) result).value()).contains("https://old.example.com/in");
    }

    @Test
    void testReadSipDomainVoiceUrl_returnsEmpty_whenNoVoiceUrl() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(DOMAINS_NO_VOICE, 200));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<Optional<String>> result =
                client.readSipDomainVoiceUrl("coldcalling-1234.sip.twilio.com");

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<Optional<String>>) result).value()).isEmpty();
    }

    @Test
    void testReadSipDomainVoiceUrl_domainNotFound_returnsErr() {
        when(restClient.request(any(Request.class)))
                .thenReturn(new Response(DOMAINS_EMPTY, 200));
        TwilioClient client = new TwilioClient(config, restClient);

        Result<Optional<String>> result =
                client.readSipDomainVoiceUrl("missing.sip.twilio.com");

        assertThat(result.isErr()).isTrue();
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
