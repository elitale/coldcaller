package com.elitale.coldbirds.coldcalling.telephony.sip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SdpBuilderTest {

    private static final String LOCAL_IP   = "192.168.1.100";
    private static final int    RTP_PORT   = 20000;
    private static final String SESSION_ID = "12345678";

    // ------------------------------------------------------------------
    // SDP offer
    // ------------------------------------------------------------------

    @Test
    void testOfferStartsWithVersionLine() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        assertThat(sdp).startsWith("v=0\r\n");
    }

    @Test
    void testOfferContainsOriginLine() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        assertThat(sdp).contains("o=coldcalling " + SESSION_ID);
    }

    @Test
    void testOfferContainsConnectionLine() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        assertThat(sdp).contains("c=IN IP4 " + LOCAL_IP);
    }

    @Test
    void testOfferContainsMediaLine() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        assertThat(sdp).contains("m=audio " + RTP_PORT + " RTP/AVP 0");
    }

    @Test
    void testOfferContainsPcmuAttribute() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        // Payload type 0 = PCMU/8000
        assertThat(sdp).contains("a=rtpmap:0 PCMU/8000");
    }

    @Test
    void testOfferContainsSendRecvAttribute() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        assertThat(sdp).contains("a=sendrecv");
    }

    @Test
    void testOfferUsesCorrectLineEndings() {
        String sdp = SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, SESSION_ID);
        // SDP lines must be separated by CRLF (RFC 4566)
        assertThat(sdp).contains("\r\n");
        assertThat(sdp).doesNotContain("\r\n\r\n\r\n"); // no double blank lines
    }

    // ------------------------------------------------------------------
    // Null / invalid guard
    // ------------------------------------------------------------------

    @Test
    void testNullIpThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> SdpBuilder.buildOffer(null, RTP_PORT, SESSION_ID));
    }

    @Test
    void testNullSessionIdThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> SdpBuilder.buildOffer(LOCAL_IP, RTP_PORT, null));
    }

    @Test
    void testNegativePortThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SdpBuilder.buildOffer(LOCAL_IP, -1, SESSION_ID));
    }

    @Test
    void testZeroPortThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SdpBuilder.buildOffer(LOCAL_IP, 0, SESSION_ID));
    }

    // ------------------------------------------------------------------
    // SIP credentials
    // ------------------------------------------------------------------

    @Test
    void testSipCredentialsNullUsernameThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SipCredentials(null, "pass", "sip.twilio.com", "sip.twilio.com", 5060));
    }

    @Test
    void testSipCredentialsNullPasswordThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SipCredentials("user", null, "sip.twilio.com", "sip.twilio.com", 5060));
    }

    @Test
    void testSipCredentialsNullDomainThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SipCredentials("user", "pass", null, "sip.twilio.com", 5060));
    }

    @Test
    void testSipCredentialsInvalidPortThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SipCredentials("user", "pass", "sip.twilio.com", "sip.twilio.com", 0));
    }

    @Test
    void testSipCredentialsValid() {
        SipCredentials creds = new SipCredentials("alice", "secret", "sip.twilio.com", "sip.twilio.com", 5060);
        assertThat(creds.username()).isEqualTo("alice");
        assertThat(creds.domain()).isEqualTo("sip.twilio.com");
        assertThat(creds.proxyPort()).isEqualTo(5060);
    }
}
