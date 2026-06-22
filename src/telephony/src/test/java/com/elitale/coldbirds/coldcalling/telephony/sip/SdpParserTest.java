package com.elitale.coldbirds.coldcalling.telephony.sip;

import com.elitale.coldbirds.coldcalling.telephony.sip.SdpParser.MediaTarget;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class SdpParserTest {

    @Test
    void parsesSessionLevelConnectionAndAudioPort() {
        final String sdp = String.join("\r\n",
                "v=0",
                "o=- 12345 1 IN IP4 203.0.113.10",
                "s=-",
                "c=IN IP4 203.0.113.10",
                "t=0 0",
                "m=audio 16384 RTP/AVP 0",
                "a=rtpmap:0 PCMU/8000",
                "");

        final Optional<MediaTarget> target = SdpParser.parseAudio(sdp);

        assertThat(target).contains(new MediaTarget("203.0.113.10", 16384));
    }

    @Test
    void mediaLevelConnectionOverridesSessionLevel() {
        final String sdp = String.join("\n",
                "v=0",
                "c=IN IP4 10.0.0.1",
                "m=audio 20000 RTP/AVP 0",
                "c=IN IP4 198.51.100.7",
                "a=rtpmap:0 PCMU/8000");

        assertThat(SdpParser.parseAudio(sdp))
                .contains(new MediaTarget("198.51.100.7", 20000));
    }

    @Test
    void emptyWhenNoAudioMediaLine() {
        final String sdp = String.join("\r\n",
                "v=0",
                "c=IN IP4 203.0.113.10",
                "m=video 5000 RTP/AVP 96");

        assertThat(SdpParser.parseAudio(sdp)).isEmpty();
    }

    @Test
    void emptyWhenNoConnectionLine() {
        final String sdp = String.join("\r\n",
                "v=0",
                "m=audio 16384 RTP/AVP 0");

        assertThat(SdpParser.parseAudio(sdp)).isEmpty();
    }

    @Test
    void emptyWhenAudioPortZero() {
        final String sdp = String.join("\r\n",
                "c=IN IP4 203.0.113.10",
                "m=audio 0 RTP/AVP 0");

        assertThat(SdpParser.parseAudio(sdp)).isEmpty();
    }

    @Test
    void parsesCryptoInlineKeyForSupportedSuite() {
        // 30 zero bytes (16-byte key + 14-byte salt) → 40 'A' chars, no padding.
        final String inline = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        final String sdp = String.join("\r\n",
                "v=0",
                "c=IN IP4 203.0.113.10",
                "m=audio 16384 RTP/SAVP 0",
                "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:" + inline + "|2^20|1:4",
                "a=rtpmap:0 PCMU/8000");

        assertThat(SdpParser.parseCrypto(sdp))
                .map(SrtpKeyToInline::of)
                .contains(inline);
    }

    @Test
    void emptyWhenCryptoSuiteUnsupported() {
        final String sdp = String.join("\r\n",
                "m=audio 16384 RTP/SAVP 0",
                "a=crypto:1 AES_256_CM_HMAC_SHA1_80 inline:AAAA");

        assertThat(SdpParser.parseCrypto(sdp)).isEmpty();
    }

    @Test
    void emptyWhenNoCryptoLine() {
        final String sdp = String.join("\r\n",
                "c=IN IP4 203.0.113.10",
                "m=audio 16384 RTP/AVP 0");

        assertThat(SdpParser.parseCrypto(sdp)).isEmpty();
    }

    /** Round-trips an {@link com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpKey} back to its inline form. */
    private interface SrtpKeyToInline {
        static String of(final com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpKey key) {
            return key.toInline();
        }
    }
}
