package com.elitale.coldbirds.coldcalling.telephony.sip;

import javax.sip.header.AuthorizationHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ProxyAuthenticateHeader;
import javax.sip.header.ProxyAuthorizationHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MD5 HTTP Digest authentication for SIP (RFC 2617), used to answer
 * {@code 401 Unauthorized} and {@code 407 Proxy Authentication Required}
 * challenges for both REGISTER and INVITE.
 *
 * <p>Supports {@code qop=auth} (and legacy no-qop). MD5 only — the only
 * algorithm Twilio's SIP registrar issues.
 */
public final class SipDigestAuth {

    private SipDigestAuth() {}

    /**
     * Compute the digest {@code response} value (RFC 2617 §3.2.2.1).
     * Pure function — unit testable against published vectors.
     *
     * @param username SIP auth username
     * @param password SIP auth password
     * @param realm    challenge realm
     * @param nonce    challenge nonce
     * @param method   SIP method (e.g. {@code REGISTER}, {@code INVITE})
     * @param uri      digest-uri (e.g. {@code sip:domain})
     * @param useQop   true to use {@code qop=auth}
     * @param nc       nonce count (hex, e.g. {@code 00000001}); ignored if !useQop
     * @param cnonce   client nonce; ignored if !useQop
     * @return 32-char lowercase hex MD5 response
     */
    public static String digestResponse(
            final String username, final String password, final String realm,
            final String nonce, final String method, final String uri,
            final boolean useQop, final String nc, final String cnonce) {

        final String ha1 = md5(username + ":" + realm + ":" + password);
        final String ha2 = md5(method + ":" + uri);
        return useQop
                ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);
    }

    /**
     * Build an {@code Authorization} (or {@code Proxy-Authorization}) header that
     * answers the given challenge response.
     *
     * @param challenge the 401/407 response carrying the WWW-/Proxy-Authenticate header
     * @param proxy     true for a 407 (proxy) challenge, false for a 401
     * @param username  SIP auth username
     * @param password  SIP auth password
     * @param method    SIP method being authenticated
     * @param uri       digest-uri for the request being authenticated
     * @param hf        JAIN-SIP header factory
     * @return a parsed credentials header ready to add to the retried request
     * @throws Exception if the header cannot be parsed
     */
    public static Header answer(
            final Response challenge, final boolean proxy,
            final String username, final String password,
            final String method, final String uri,
            final HeaderFactory hf) throws Exception {

        Objects.requireNonNull(challenge, "challenge must not be null");
        Objects.requireNonNull(hf,        "hf must not be null");

        final String realm;
        final String nonce;
        final String qop;
        final String opaque;
        final String algorithm;
        if (proxy) {
            final ProxyAuthenticateHeader c =
                    (ProxyAuthenticateHeader) challenge.getHeader(ProxyAuthenticateHeader.NAME);
            realm = c.getRealm(); nonce = c.getNonce(); qop = c.getQop();
            opaque = c.getOpaque(); algorithm = c.getAlgorithm();
        } else {
            final WWWAuthenticateHeader c =
                    (WWWAuthenticateHeader) challenge.getHeader(WWWAuthenticateHeader.NAME);
            realm = c.getRealm(); nonce = c.getNonce(); qop = c.getQop();
            opaque = c.getOpaque(); algorithm = c.getAlgorithm();
        }

        final boolean useQop = qop != null && Arrays.stream(qop.split(","))
                .anyMatch(q -> q.trim().equalsIgnoreCase("auth"));
        final String nc     = "00000001";
        final String cnonce = Integer.toHexString(ThreadLocalRandom.current().nextInt());

        final String responseDigest = digestResponse(
                username, password, realm, nonce, method, uri, useQop, nc, cnonce);

        final StringBuilder value = new StringBuilder("Digest ")
                .append("username=\"").append(username).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonce).append("\", ")
                .append("uri=\"").append(uri).append("\", ")
                .append("response=\"").append(responseDigest).append("\"");
        if (algorithm != null) {
            value.append(", algorithm=").append(algorithm);
        }
        if (useQop) {
            value.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
        }
        if (opaque != null && !opaque.isEmpty()) {
            value.append(", opaque=\"").append(opaque).append("\"");
        }

        final String headerName = proxy ? ProxyAuthorizationHeader.NAME : AuthorizationHeader.NAME;
        return hf.createHeader(headerName, value.toString());
    }

    private static String md5(final String input) {
        try {
            final byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable", e);
        }
    }
}
