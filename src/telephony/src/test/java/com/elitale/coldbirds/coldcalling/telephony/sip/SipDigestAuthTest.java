package com.elitale.coldbirds.coldcalling.telephony.sip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the MD5 digest math against the published RFC 2617 §3.5 vector. */
final class SipDigestAuthTest {

    // RFC 2617 §3.5 worked example.
    private static final String USER   = "Mufasa";
    private static final String PASS   = "Circle Of Life";
    private static final String REALM  = "testrealm@host.com";
    private static final String NONCE  = "dcd98b7102dd2f0e8b11d0f600bfb0c093";
    private static final String URI    = "/dir/index.html";
    private static final String METHOD = "GET";
    private static final String NC     = "00000001";
    private static final String CNONCE = "0a4f113b";

    @Test
    void digestResponse_matchesRfc2617QopVector() {
        final String response = SipDigestAuth.digestResponse(
                USER, PASS, REALM, NONCE, METHOD, URI, true, NC, CNONCE);

        assertThat(response).isEqualTo("6629fae49393a05397450978507c4ef1");
    }

    @Test
    void digestResponse_noQop_isDistinct32HexDigest() {
        final String response = SipDigestAuth.digestResponse(
                USER, PASS, REALM, NONCE, METHOD, URI, false, NC, CNONCE);

        assertThat(response)
                .hasSize(32)
                .matches("[0-9a-f]{32}")
                .isNotEqualTo("6629fae49393a05397450978507c4ef1");
    }

    @Test
    void digestResponse_changesWithPassword() {
        final String right = SipDigestAuth.digestResponse(
                USER, PASS, REALM, NONCE, METHOD, URI, true, NC, CNONCE);
        final String wrong = SipDigestAuth.digestResponse(
                USER, "wrong", REALM, NONCE, METHOD, URI, true, NC, CNONCE);

        assertThat(wrong).isNotEqualTo(right);
    }
}
