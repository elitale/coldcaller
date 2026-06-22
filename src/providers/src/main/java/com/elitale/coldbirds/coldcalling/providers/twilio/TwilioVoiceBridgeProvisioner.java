package com.elitale.coldbirds.coldcalling.providers.twilio;

import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deploys the PSTN-bridge Twilio Serverless Function <em>into the user's own
 * Twilio account</em> and returns its per-account invocation URL.
 *
 * <p>AUTO call-routing cannot point a SIP domain at a single shared bridge: the
 * function runs with {@code protected} visibility, so Twilio validates the Voice
 * webhook signature against the <em>owning</em> account's auth token. A bring-your-own
 * Twilio tenant therefore needs the bridge deployed under their own account. This
 * provisioner ports {@code .scripts/setup-sip-pstn-handler.js} to Java: ensure the
 * Serverless service, ensure the function, upload a version of the bundled
 * {@code pstn-bridge.js}, build it, wait for the build, ensure the {@code production}
 * environment, deploy, and hand back {@code https://{domain}/pstn-bridge}.
 *
 * <p>The Twilio Java SDK can't perform the multipart Serverless code upload, so the
 * Serverless REST calls go through a small injectable {@link Transport} backed by
 * {@link java.net.http.HttpClient}. The bridge endpoint is then wired onto the SIP
 * domain separately by {@code CallRoutingService} via {@code TwilioClient}.
 */
public final class TwilioVoiceBridgeProvisioner {

    static final String SERVICE_UNIQUE_NAME = "coldcalling-sip";
    static final String FUNCTION_NAME       = "pstn-bridge";
    static final String FUNCTION_PATH       = "/pstn-bridge";
    static final String ENV_UNIQUE_NAME     = "production";
    static final String ENV_DOMAIN_SUFFIX   = "prod";
    static final String VISIBILITY          = "protected";

    private static final String SLS_BASE        = "https://serverless.twilio.com";
    private static final String SLS_UPLOAD_BASE = "https://serverless-upload.twilio.com";
    private static final String FUNCTION_RESOURCE = "/twilio/pstn-bridge.js";

    private static final long POLL_INTERVAL_MS      = 2_000L;
    private static final int  DEFAULT_MAX_BUILD_POLLS = 90;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Transport transport;
    private final Sleeper sleeper;
    private final int maxBuildPolls;

    /** Production constructor — talks to Twilio over HTTPS using the given credentials. */
    public TwilioVoiceBridgeProvisioner(final TwilioConfig config) {
        this(new HttpClientTransport(Objects.requireNonNull(config, "config must not be null")),
                Thread::sleep, DEFAULT_MAX_BUILD_POLLS);
    }

    /** Test/seam constructor — inject the transport, a sleeper, and a small poll cap. */
    TwilioVoiceBridgeProvisioner(final Transport transport, final Sleeper sleeper, final int maxBuildPolls) {
        this.transport     = Objects.requireNonNull(transport, "transport must not be null");
        this.sleeper       = Objects.requireNonNull(sleeper, "sleeper must not be null");
        this.maxBuildPolls = maxBuildPolls;
    }

    /**
     * Deploy (or re-deploy) the bridge and return its function URL.
     *
     * @return {@link Result.Ok} with {@code https://{domain}/pstn-bridge}, or
     *         {@link Result.Err} describing why the deploy failed
     */
    public Result<String> provisionBridge() {
        final String source;
        try {
            source = loadBundledSource();
        } catch (final IOException missing) {
            return Result.err("Bundled PSTN bridge source is missing from the app package.", missing);
        }
        try {
            final String serviceSid  = ensureService();
            final String functionSid = ensureFunction(serviceSid);
            final String versionSid  = uploadVersion(serviceSid, functionSid, source);
            final String buildSid    = createBuild(serviceSid, versionSid);
            awaitBuild(serviceSid, buildSid);
            final Environment environment = ensureEnvironment(serviceSid);
            deploy(serviceSid, environment.sid(), buildSid);
            return Result.ok("https://" + environment.domainName() + FUNCTION_PATH);
        } catch (final ProvisionException failed) {
            return Result.err(failed.getMessage(), failed.getCause());
        } catch (final TransportException network) {
            return Result.err("Network error talking to Twilio Serverless: " + network.getMessage(), network);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.err("Bridge deployment was interrupted before it finished.", interrupted);
        }
    }

    // ── deploy steps ─────────────────────────────────────────────────────────

    private String ensureService() throws ProvisionException {
        final JsonNode listed = parse(transport.get(SLS_BASE + "/v1/Services?PageSize=100"),
                "list serverless services");
        final Optional<String> existing = sidOf(listed, "services", "unique_name", SERVICE_UNIQUE_NAME);
        if (existing.isPresent()) {
            return existing.get();
        }
        return requireSid(parse(transport.postForm(SLS_BASE + "/v1/Services", Map.of(
                "UniqueName", SERVICE_UNIQUE_NAME,
                "FriendlyName", "coldCalling SIP",
                "IncludeCredentials", "true",
                "UiEditable", "true")), "create the serverless service"));
    }

    private String ensureFunction(final String serviceSid) throws ProvisionException {
        final String base = SLS_BASE + "/v1/Services/" + serviceSid + "/Functions";
        final JsonNode listed = parse(transport.get(base + "?PageSize=100"), "list serverless functions");
        final Optional<String> existing = sidOf(listed, "functions", "friendly_name", FUNCTION_NAME);
        if (existing.isPresent()) {
            return existing.get();
        }
        return requireSid(parse(transport.postForm(base, Map.of("FriendlyName", FUNCTION_NAME)),
                "create the serverless function"));
    }

    private String uploadVersion(final String serviceSid, final String functionSid, final String source)
            throws ProvisionException {
        final String url = SLS_UPLOAD_BASE + "/v1/Services/" + serviceSid
                + "/Functions/" + functionSid + "/Versions";
        return requireSid(parse(transport.postMultipart(url,
                Map.of("Path", FUNCTION_PATH, "Visibility", VISIBILITY),
                "Content", FUNCTION_NAME + ".js", source.getBytes(StandardCharsets.UTF_8)),
                "upload the bridge code"));
    }

    private String createBuild(final String serviceSid, final String versionSid) throws ProvisionException {
        return requireSid(parse(transport.postForm(SLS_BASE + "/v1/Services/" + serviceSid + "/Builds",
                Map.of("FunctionVersions", versionSid)), "start the serverless build"));
    }

    private void awaitBuild(final String serviceSid, final String buildSid)
            throws ProvisionException, InterruptedException {
        final String url = SLS_BASE + "/v1/Services/" + serviceSid + "/Builds/" + buildSid;
        for (int attempt = 0; attempt < maxBuildPolls; attempt++) {
            final String status = parse(transport.get(url), "check the serverless build status")
                    .path("status").asText();
            if ("completed".equals(status)) {
                return;
            }
            if ("failed".equals(status)) {
                throw new ProvisionException("Twilio Serverless build failed.");
            }
            if (attempt < maxBuildPolls - 1) {
                sleeper.sleep(POLL_INTERVAL_MS);
            }
        }
        throw new ProvisionException("Timed out waiting for the Twilio Serverless build.");
    }

    private Environment ensureEnvironment(final String serviceSid) throws ProvisionException {
        final String base = SLS_BASE + "/v1/Services/" + serviceSid + "/Environments";
        final JsonNode listed = parse(transport.get(base + "?PageSize=100"), "list serverless environments");
        for (final JsonNode node : listed.path("environments")) {
            if (ENV_UNIQUE_NAME.equals(node.path("unique_name").asText())) {
                return toEnvironment(node);
            }
        }
        return toEnvironment(parse(transport.postForm(base, Map.of(
                "UniqueName", ENV_UNIQUE_NAME,
                "DomainSuffix", ENV_DOMAIN_SUFFIX)), "create the serverless environment"));
    }

    private void deploy(final String serviceSid, final String environmentSid, final String buildSid)
            throws ProvisionException {
        parse(transport.postForm(SLS_BASE + "/v1/Services/" + serviceSid
                        + "/Environments/" + environmentSid + "/Deployments",
                Map.of("BuildSid", buildSid)), "deploy the serverless build");
    }

    // ── parsing helpers ────────────────────────────────────────────────────────

    private JsonNode parse(final HttpReply reply, final String step) throws ProvisionException {
        if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
            throw new ProvisionException("Couldn't " + step + " (HTTP " + reply.statusCode()
                    + "): " + summary(reply.body()));
        }
        try {
            return MAPPER.readTree(reply.body() == null ? "{}" : reply.body());
        } catch (final JsonProcessingException malformed) {
            throw new ProvisionException("Unexpected Twilio response while trying to " + step + ".", malformed);
        }
    }

    private static Optional<String> sidOf(final JsonNode root, final String arrayField,
                                          final String matchField, final String value) {
        for (final JsonNode node : root.path(arrayField)) {
            if (value.equals(node.path(matchField).asText())) {
                final String sid = node.path("sid").asText();
                if (!sid.isEmpty()) {
                    return Optional.of(sid);
                }
            }
        }
        return Optional.empty();
    }

    private static String requireSid(final JsonNode node) throws ProvisionException {
        final String sid = node.path("sid").asText();
        if (sid.isEmpty()) {
            throw new ProvisionException("Twilio response did not include a resource SID.");
        }
        return sid;
    }

    private static Environment toEnvironment(final JsonNode node) throws ProvisionException {
        final String sid = node.path("sid").asText();
        final String domain = node.path("domain_name").asText();
        if (sid.isEmpty() || domain.isEmpty()) {
            throw new ProvisionException("Twilio environment response was missing its sid or domain_name.");
        }
        return new Environment(sid, domain);
    }

    private static String summary(final String body) {
        if (body == null || body.isBlank()) {
            return "(no response body)";
        }
        final String trimmed = body.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }

    private static String loadBundledSource() throws IOException {
        try (InputStream in = TwilioVoiceBridgeProvisioner.class.getResourceAsStream(FUNCTION_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled bridge source not found on classpath: " + FUNCTION_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Environment(String sid, String domainName) {}

    /** Raised internally when a Serverless step returns a non-2xx or malformed response. */
    private static final class ProvisionException extends Exception {
        ProvisionException(final String message) {
            super(message);
        }

        ProvisionException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    // ── transport seam ─────────────────────────────────────────────────────────

    /** Minimal HTTP seam over the Serverless REST API; the only collaborator stubbed in tests. */
    interface Transport {
        HttpReply get(String url);

        HttpReply postForm(String url, Map<String, String> fields);

        HttpReply postMultipart(String url, Map<String, String> fields,
                                String fileField, String fileName, byte[] content);
    }

    /** A raw HTTP response: status code plus the (possibly empty) body. */
    record HttpReply(int statusCode, String body) {}

    /** Thrown by a {@link Transport} when the underlying HTTP call cannot complete. */
    static final class TransportException extends RuntimeException {
        TransportException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** Pause between build-status polls; injected so tests don't actually sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Default {@link Transport} backed by {@link HttpClient} with HTTP Basic auth. */
    private static final class HttpClientTransport implements Transport {

        private final String authHeader;
        private final HttpClient http;

        HttpClientTransport(final TwilioConfig config) {
            final String creds = config.accountSid() + ":" + config.authToken();
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        }

        @Override
        public HttpReply get(final String url) {
            return send(authed(url).GET().build());
        }

        @Override
        public HttpReply postForm(final String url, final Map<String, String> fields) {
            return send(authed(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encodeForm(fields), StandardCharsets.UTF_8))
                    .build());
        }

        @Override
        public HttpReply postMultipart(final String url, final Map<String, String> fields,
                                       final String fileField, final String fileName, final byte[] content) {
            final String boundary = "----coldcalling" + Long.toHexString(System.nanoTime());
            final byte[] body = multipartBody(boundary, fields, fileField, fileName, content);
            return send(authed(url)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build());
        }

        private HttpRequest.Builder authed(final String url) {
            return HttpRequest.newBuilder(URI.create(url)).header("Authorization", authHeader);
        }

        private HttpReply send(final HttpRequest request) {
            try {
                final HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new HttpReply(response.statusCode(), response.body());
            } catch (final IOException io) {
                throw new TransportException(io.getMessage(), io);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TransportException("request interrupted", interrupted);
            }
        }

        private static String encodeForm(final Map<String, String> fields) {
            final StringBuilder sb = new StringBuilder();
            for (final Map.Entry<String, String> field : fields.entrySet()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
            }
            return sb.toString();
        }

        private static byte[] multipartBody(final String boundary, final Map<String, String> fields,
                                            final String fileField, final String fileName, final byte[] content) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                for (final Map.Entry<String, String> field : fields.entrySet()) {
                    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write((field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + fileField
                        + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write("Content-Type: application/javascript\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.write(content);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            } catch (final IOException assembly) {
                throw new TransportException("failed to assemble upload body", assembly);
            }
            return out.toByteArray();
        }
    }
}
