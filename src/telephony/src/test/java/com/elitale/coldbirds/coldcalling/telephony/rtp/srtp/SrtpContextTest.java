package com.elitale.coldbirds.coldcalling.telephony.rtp.srtp;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SRTP {@code AES_CM_128_HMAC_SHA1_80} crypto tests, validated against the
 * published RFC 3711 test vectors (§B.2 AES-CM keystream, §B.3 key derivation),
 * plus protect/unprotect round-trip and tamper-detection.
 */
final class SrtpContextTest {

    private static final HexFormat HEX = HexFormat.of();

    // RFC 3711 §B.3 — key derivation (kdr = 0)
    private static final byte[] MASTER_KEY  = HEX.parseHex("e1f97a0d3e018be0d64fa32c06de4139");
    private static final byte[] MASTER_SALT = HEX.parseHex("0ec675ad498afeebb6960b3aabe6");

    @Test
    void deriveKey_encryptionKey_matchesRfc3711VectorB3() throws Exception {
        final byte[] k_e = SrtpContext.deriveKey(MASTER_KEY, MASTER_SALT, (byte) 0x00, 16);
        assertThat(HEX.formatHex(k_e)).isEqualTo("c61e7a93744f39ee10734afe3ff7a087");
    }

    @Test
    void deriveKey_authKey_matchesRfc3711VectorB3() throws Exception {
        final byte[] k_a = SrtpContext.deriveKey(MASTER_KEY, MASTER_SALT, (byte) 0x01, 20);
        assertThat(HEX.formatHex(k_a)).isEqualTo("cebe321f6ff7716b6fd4ab49af256a156d38baa4");
    }

    @Test
    void deriveKey_saltKey_matchesRfc3711VectorB3() throws Exception {
        final byte[] k_s = SrtpContext.deriveKey(MASTER_KEY, MASTER_SALT, (byte) 0x02, 14);
        assertThat(HEX.formatHex(k_s)).isEqualTo("30cbbc08863d8c85d49db34a9ae1");
    }

    @Test
    void protect_producesRfc3711VectorB2Keystream() {
        // RFC 3711 §B.2: AES-CM keystream with SSRC=0, packet index=0.
        final byte[] sessionKey  = HEX.parseHex("2b7e151628aed2a6abf7158809cf4f3c");
        final byte[] sessionSalt = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9fafbfcfd");
        final byte[] authKey     = new byte[20]; // irrelevant for this test
        final SrtpContext ctx = SrtpContext.withSessionKeys(sessionKey, sessionSalt, authKey);

        // 12-byte header (SSRC=0, seq=0) + 16 zero payload bytes → ciphertext == keystream.
        final byte[] rtp = new byte[12 + 16];
        rtp[0] = (byte) 0x80; // V=2

        final byte[] srtp = ctx.protect(rtp);
        final byte[] ciphertext = new byte[16];
        System.arraycopy(srtp, 12, ciphertext, 0, 16);

        assertThat(HEX.formatHex(ciphertext)).isEqualTo("e03ead0935c95e80e166b16dd92b4eb4");
    }

    @Test
    void protectThenUnprotect_roundTripsPlaintext() throws Exception {
        final byte[] key  = HEX.parseHex("e1f97a0d3e018be0d64fa32c06de4139");
        final byte[] salt = HEX.parseHex("0ec675ad498afeebb6960b3aabe6");
        final SrtpContext sender   = SrtpContext.fromMaster(key, salt);
        final SrtpContext receiver = SrtpContext.fromMaster(key, salt);

        final byte[] rtp = buildRtp(1234, 0x11223344L, "hello srtp payload".getBytes());
        final byte[] srtp = sender.protect(rtp);

        assertThat(srtp).hasSize(rtp.length + SrtpContext.AUTH_TAG_LEN);
        assertThat(receiver.unprotect(srtp)).containsExactly(rtp);
    }

    @Test
    void unprotect_rejectsTamperedPayload() {
        final byte[] key  = HEX.parseHex("e1f97a0d3e018be0d64fa32c06de4139");
        final byte[] salt = HEX.parseHex("0ec675ad498afeebb6960b3aabe6");
        final SrtpContext sender   = SrtpContext.fromMaster(key, salt);
        final SrtpContext receiver = SrtpContext.fromMaster(key, salt);

        final byte[] srtp = sender.protect(buildRtp(7, 0xAABBCCDDL, new byte[160]));
        srtp[15] ^= 0x01; // flip a ciphertext bit

        assertThatThrownBy(() -> receiver.unprotect(srtp))
                .isInstanceOf(SrtpException.class);
    }

    private static byte[] buildRtp(final int seq, final long ssrc, final byte[] payload) {
        final byte[] rtp = new byte[12 + payload.length];
        rtp[0] = (byte) 0x80;
        rtp[1] = 0x00; // PT 0 = PCMU
        rtp[2] = (byte) (seq >>> 8);
        rtp[3] = (byte) seq;
        rtp[8]  = (byte) (ssrc >>> 24);
        rtp[9]  = (byte) (ssrc >>> 16);
        rtp[10] = (byte) (ssrc >>> 8);
        rtp[11] = (byte) ssrc;
        System.arraycopy(payload, 0, rtp, 12, payload.length);
        return rtp;
    }
}
