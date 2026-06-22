package com.elitale.coldbirds.coldcalling.providers.twilio;

import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioVoiceBridgeProvisioner.HttpReply;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioVoiceBridgeProvisioner.Transport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwilioVoiceBridgeProvisionerTest {

    private static final String SLS    = "https://serverless.twilio.com";
    private static final String UPLOAD = "https://serverless-upload.twilio.com";

    private static final String SVC_SID   = "ZS00000000000000000000000000000001";
    private static final String FN_SID    = "ZH00000000000000000000000000000001";
    private static final String VER_SID   = "ZN00000000000000000000000000000001";
    private static final String BUILD_SID = "ZB00000000000000000000000000000001";
    private static final String ENV_SID   = "ZE00000000000000000000000000000001";
    private static final String DOMAIN    = "coldcalling-sip-9999-prod.twil.io";

    private static final String LIST_SERVICES  = SLS + "/v1/Services?PageSize=100";
    private static final String CREATE_SERVICE = SLS + "/v1/Services";
    private static final String LIST_FUNCTIONS  = SLS + "/v1/Services/" + SVC_SID + "/Functions?PageSize=100";
    private static final String CREATE_FUNCTION = SLS + "/v1/Services/" + SVC_SID + "/Functions";
    private static final String UPLOAD_VERSION  = UPLOAD + "/v1/Services/" + SVC_SID + "/Functions/" + FN_SID + "/Versions";
    private static final String CREATE_BUILD = SLS + "/v1/Services/" + SVC_SID + "/Builds";
    private static final String POLL_BUILD   = SLS + "/v1/Services/" + SVC_SID + "/Builds/" + BUILD_SID;
    private static final String LIST_ENVS    = SLS + "/v1/Services/" + SVC_SID + "/Environments?PageSize=100";
    private static final String CREATE_ENV   = SLS + "/v1/Services/" + SVC_SID + "/Environments";
    private static final String DEPLOY       = SLS + "/v1/Services/" + SVC_SID + "/Environments/" + ENV_SID + "/Deployments";

    private TwilioVoiceBridgeProvisioner provisioner(Transport transport, int maxBuildPolls) {
        return new TwilioVoiceBridgeProvisioner(transport, millis -> { /* no sleep in tests */ }, maxBuildPolls);
    }

    private void stubFreshDeploy(FakeTransport t) {
        t.on("GET " + LIST_SERVICES, ok("{\"services\":[]}"));
        t.on("POST " + CREATE_SERVICE, ok("{\"sid\":\"" + SVC_SID + "\"}"));
        t.on("GET " + LIST_FUNCTIONS, ok("{\"functions\":[]}"));
        t.on("POST " + CREATE_FUNCTION, ok("{\"sid\":\"" + FN_SID + "\"}"));
        t.on("POST_MULTIPART " + UPLOAD_VERSION, ok("{\"sid\":\"" + VER_SID + "\"}"));
        t.on("POST " + CREATE_BUILD, ok("{\"sid\":\"" + BUILD_SID + "\"}"));
        t.on("GET " + POLL_BUILD, ok("{\"status\":\"completed\"}"));
        t.on("GET " + LIST_ENVS, ok("{\"environments\":[]}"));
        t.on("POST " + CREATE_ENV, ok("{\"sid\":\"" + ENV_SID + "\",\"domain_name\":\"" + DOMAIN + "\"}"));
        t.on("POST " + DEPLOY, ok("{\"sid\":\"ZD00000000000000000000000000000001\"}"));
    }

    @Test
    void provisionBridge_createsAllResources_whenNoneExist_returnsFunctionUrl() {
        final FakeTransport t = new FakeTransport();
        stubFreshDeploy(t);

        final Result<String> result = provisioner(t, 90).provisionBridge();

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<String>) result).value())
                .isEqualTo("https://" + DOMAIN + "/pstn-bridge");
        // created every resource because none pre-existed
        assertThat(t.calls).contains(
                "POST " + CREATE_SERVICE,
                "POST " + CREATE_FUNCTION,
                "POST_MULTIPART " + UPLOAD_VERSION,
                "POST " + CREATE_BUILD,
                "POST " + CREATE_ENV,
                "POST " + DEPLOY);
    }

    @Test
    void provisionBridge_reusesExistingResources_skipsCreates() {
        final FakeTransport t = new FakeTransport();
        t.on("GET " + LIST_SERVICES,
                ok("{\"services\":[{\"unique_name\":\"coldcalling-sip\",\"sid\":\"" + SVC_SID + "\"}]}"));
        t.on("GET " + LIST_FUNCTIONS,
                ok("{\"functions\":[{\"friendly_name\":\"pstn-bridge\",\"sid\":\"" + FN_SID + "\"}]}"));
        t.on("POST_MULTIPART " + UPLOAD_VERSION, ok("{\"sid\":\"" + VER_SID + "\"}"));
        t.on("POST " + CREATE_BUILD, ok("{\"sid\":\"" + BUILD_SID + "\"}"));
        t.on("GET " + POLL_BUILD, ok("{\"status\":\"completed\"}"));
        t.on("GET " + LIST_ENVS,
                ok("{\"environments\":[{\"unique_name\":\"production\",\"sid\":\"" + ENV_SID
                        + "\",\"domain_name\":\"" + DOMAIN + "\"}]}"));
        t.on("POST " + DEPLOY, ok("{\"sid\":\"ZD1\"}"));

        final Result<String> result = provisioner(t, 90).provisionBridge();

        assertThat(result.isOk()).isTrue();
        assertThat(((Result.Ok<String>) result).value())
                .isEqualTo("https://" + DOMAIN + "/pstn-bridge");
        assertThat(t.calls).doesNotContain(
                "POST " + CREATE_SERVICE,
                "POST " + CREATE_FUNCTION,
                "POST " + CREATE_ENV);
    }

    @Test
    void provisionBridge_pollsBuild_untilCompleted() {
        final FakeTransport t = new FakeTransport();
        stubFreshDeploy(t);
        // first two polls building, then completed
        t.on("GET " + POLL_BUILD,
                ok("{\"status\":\"building\"}"),
                ok("{\"status\":\"building\"}"),
                ok("{\"status\":\"completed\"}"));

        final Result<String> result = provisioner(t, 90).provisionBridge();

        assertThat(result.isOk()).isTrue();
        assertThat(t.countOf("GET " + POLL_BUILD)).isEqualTo(3);
    }

    @Test
    void provisionBridge_returnsErr_whenBuildFails() {
        final FakeTransport t = new FakeTransport();
        stubFreshDeploy(t);
        t.on("GET " + POLL_BUILD, ok("{\"status\":\"failed\"}"));

        final Result<String> result = provisioner(t, 90).provisionBridge();

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).containsIgnoringCase("build failed");
        assertThat(t.calls).doesNotContain("POST " + DEPLOY);
    }

    @Test
    void provisionBridge_returnsErr_whenBuildTimesOut() {
        final FakeTransport t = new FakeTransport();
        stubFreshDeploy(t);
        t.on("GET " + POLL_BUILD, ok("{\"status\":\"building\"}"));

        final Result<String> result = provisioner(t, 2).provisionBridge();

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).containsIgnoringCase("timed out");
        assertThat(t.countOf("GET " + POLL_BUILD)).isEqualTo(2);
    }

    @Test
    void provisionBridge_returnsErr_whenServiceListFails() {
        final FakeTransport t = new FakeTransport();
        t.on("GET " + LIST_SERVICES, new HttpReply(401, "{\"message\":\"Authenticate\"}"));

        final Result<String> result = provisioner(t, 90).provisionBridge();

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).contains("401");
    }

    @Test
    void provisionBridge_returnsErr_whenTransportThrows() {
        final Transport throwing = new Transport() {
            @Override public HttpReply get(String url) {
                throw new TwilioVoiceBridgeProvisioner.TransportException("connection reset", null);
            }
            @Override public HttpReply postForm(String url, Map<String, String> fields) {
                throw new AssertionError("unexpected");
            }
            @Override public HttpReply postMultipart(String url, Map<String, String> fields,
                                                     String fileField, String fileName, byte[] content) {
                throw new AssertionError("unexpected");
            }
        };

        final Result<String> result = provisioner(throwing, 90).provisionBridge();

        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<String>) result).message()).containsIgnoringCase("network");
    }

    @Test
    void provisionBridge_uploadsBundledSource_asProtectedVersion() {
        final FakeTransport t = new FakeTransport();
        stubFreshDeploy(t);

        provisioner(t, 90).provisionBridge();

        assertThat(t.lastMultipartFields).containsEntry("Path", "/pstn-bridge");
        assertThat(t.lastMultipartFields).containsEntry("Visibility", "protected");
        assertThat(t.lastMultipartFileName).isEqualTo("pstn-bridge.js");
        assertThat(new String(t.lastMultipartContent)).contains("exports.handler");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static HttpReply ok(String body) {
        return new HttpReply(200, body);
    }

    /** Scripted, recording {@link Transport}: maps "METHOD url" → queued replies. */
    private static final class FakeTransport implements Transport {
        private final Map<String, List<HttpReply>> scripted = new HashMap<>();
        private final Map<String, Integer> hits = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        Map<String, String> lastMultipartFields;
        String lastMultipartFileName;
        byte[] lastMultipartContent;

        void on(String key, HttpReply... replies) {
            scripted.put(key, List.of(replies));
        }

        int countOf(String key) {
            return (int) calls.stream().filter(key::equals).count();
        }

        private HttpReply next(String key) {
            calls.add(key);
            final List<HttpReply> replies = scripted.get(key);
            if (replies == null || replies.isEmpty()) {
                throw new AssertionError("Unexpected transport call: " + key);
            }
            final int i = hits.merge(key, 1, Integer::sum) - 1;
            return replies.get(Math.min(i, replies.size() - 1));
        }

        @Override
        public HttpReply get(String url) {
            return next("GET " + url);
        }

        @Override
        public HttpReply postForm(String url, Map<String, String> fields) {
            return next("POST " + url);
        }

        @Override
        public HttpReply postMultipart(String url, Map<String, String> fields,
                                       String fileField, String fileName, byte[] content) {
            this.lastMultipartFields = fields;
            this.lastMultipartFileName = fileName;
            this.lastMultipartContent = content;
            return next("POST_MULTIPART " + url);
        }
    }
}
