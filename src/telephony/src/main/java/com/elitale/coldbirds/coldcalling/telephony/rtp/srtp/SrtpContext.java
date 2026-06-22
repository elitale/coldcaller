package com.elitale.coldbirds.coldcalling.telephony.rtp.srtp;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * SRTP (RFC 3711) crypto context for the {@code AES_CM_128_HMAC_SHA1_80} suite.
 *
 * <p>Implements the only suite Twilio Secure Media negotiates over SIP:
 * <ul>
 *   <li>Encryption: AES-128 in Counter Mode (AES-CM).</li>
 *   <li>Authentication: HMAC-SHA1, 80-bit (10-byte) truncated tag.</li>
 *   <li>Master key 128-bit, master salt 112-bit; session keys derived per §4.3.</li>
 * </ul>
 *
 * <p>One context protects/unprotects packets for a single SSRC stream. A call
 * uses two contexts: one for the outbound stream (our key) and one for the
 * inbound stream (the peer's key).
 *
 * <p><strong>Not thread-safe.</strong> Each direction runs on a single thread.
 */
public final class SrtpContext {

    /** Truncated HMAC-SHA1 tag length for the _80 suite. */
    public static final int AUTH_TAG_LEN = 10;

    /** Master key length in bytes (128-bit). */
    public static final int MASTER_KEY_LEN = 16;

    /** Master salt length in bytes (112-bit). */
    public static final int MASTER_SALT_LEN = 14;

    private static final int RTP_HEADER_LEN = 12;

    // Key-derivation labels (RFC 3711 §4.3.1).
    private static final byte LABEL_ENCRYPTION = 0x00;
    private static final byte LABEL_AUTH       = 0x01;
    private static final byte LABEL_SALT       = 0x02;

    private final byte[] sessionKey;     // 16 bytes — AES-CM encryption key
    private final byte[] sessionSalt;    // 14 bytes — AES-CM salt
    private final byte[] sessionAuthKey; // 20 bytes — HMAC-SHA1 key

    private long roc      = 0;   // 32-bit rollover counter
    private int  lastSeq  = -1;  // last sequence number seen (for rollover detection)

    private SrtpContext(final byte[] sessionKey, final byte[] sessionSalt, final byte[] sessionAuthKey) {
        this.sessionKey     = sessionKey;
        this.sessionSalt    = sessionSalt;
        this.sessionAuthKey = sessionAuthKey;
    }

    /** Package-private factory for tests: build a context from raw session keys (no derivation). */
    static SrtpContext withSessionKeys(final byte[] sessionKey, final byte[] sessionSalt, final byte[] sessionAuthKey) {
        return new SrtpContext(sessionKey.clone(), sessionSalt.clone(), sessionAuthKey.clone());
    }

    /**
     * Derive a session context from a master key + salt (RFC 3711 §4.3, kdr = 0).
     *
     * @param masterKey  16-byte master key; must not be null
     * @param masterSalt 14-byte master salt; must not be null
     * @return a ready-to-use context
     */
    public static SrtpContext fromMaster(final byte[] masterKey, final byte[] masterSalt) {
        Objects.requireNonNull(masterKey,  "masterKey must not be null");
        Objects.requireNonNull(masterSalt, "masterSalt must not be null");
        if (masterKey.length != MASTER_KEY_LEN) {
            throw new IllegalArgumentException("masterKey must be " + MASTER_KEY_LEN + " bytes");
        }
        if (masterSalt.length != MASTER_SALT_LEN) {
            throw new IllegalArgumentException("masterSalt must be " + MASTER_SALT_LEN + " bytes");
        }
        try {
            final byte[] k_e = deriveKey(masterKey, masterSalt, LABEL_ENCRYPTION, 16);
            final byte[] k_a = deriveKey(masterKey, masterSalt, LABEL_AUTH,       20);
            final byte[] k_s = deriveKey(masterKey, masterSalt, LABEL_SALT,       14);
            return new SrtpContext(k_e, k_s, k_a);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("SRTP key derivation failed", e);
        }
    }

    /**
     * Protect (encrypt + authenticate) a plaintext RTP packet.
     *
     * @param rtpPacket a full RTP packet (12-byte header + payload); must not be null
     * @return a new SRTP packet (header + ciphertext + 10-byte auth tag)
     */
    public byte[] protect(final byte[] rtpPacket) {
        Objects.requireNonNull(rtpPacket, "rtpPacket must not be null");
        if (rtpPacket.length < RTP_HEADER_LEN) {
            throw new IllegalArgumentException("RTP packet shorter than header");
        }
        final int seq  = readUint16(rtpPacket, 2);
        final long ssrc = readUint32(rtpPacket, 8);
        final long packetIndex = advanceRoc(seq);

        try {
            final byte[] iv = encryptionIv(ssrc, packetIndex);
            final byte[] payload = Arrays.copyOfRange(rtpPacket, RTP_HEADER_LEN, rtpPacket.length);
            final byte[] cipher  = aesCtr(sessionKey, iv, payload);

            final byte[] out = new byte[RTP_HEADER_LEN + cipher.length + AUTH_TAG_LEN];
            System.arraycopy(rtpPacket, 0, out, 0, RTP_HEADER_LEN);
            System.arraycopy(cipher, 0, out, RTP_HEADER_LEN, cipher.length);

            final byte[] tag = authTag(out, RTP_HEADER_LEN + cipher.length, packetIndex);
            System.arraycopy(tag, 0, out, RTP_HEADER_LEN + cipher.length, AUTH_TAG_LEN);
            return out;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("SRTP protect failed", e);
        }
    }

    /**
     * Unprotect (verify + decrypt) an SRTP packet.
     *
     * @param srtpPacket a full SRTP packet (header + ciphertext + 10-byte tag); must not be null
     * @return the plaintext RTP packet (header + decrypted payload)
     * @throws SrtpException if authentication fails or the packet is malformed
     */
    public byte[] unprotect(final byte[] srtpPacket) throws SrtpException {
        Objects.requireNonNull(srtpPacket, "srtpPacket must not be null");
        if (srtpPacket.length < RTP_HEADER_LEN + AUTH_TAG_LEN) {
            throw new SrtpException("SRTP packet too short");
        }
        final int seq   = readUint16(srtpPacket, 2);
        final long ssrc = readUint32(srtpPacket, 8);
        final long packetIndex = advanceRoc(seq);

        final int authedLen = srtpPacket.length - AUTH_TAG_LEN;
        try {
            final byte[] expected = authTag(srtpPacket, authedLen, packetIndex);
            final byte[] actual   = Arrays.copyOfRange(srtpPacket, authedLen, srtpPacket.length);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new SrtpException("SRTP authentication tag mismatch");
            }
            final byte[] iv = encryptionIv(ssrc, packetIndex);
            final byte[] cipher = Arrays.copyOfRange(srtpPacket, RTP_HEADER_LEN, authedLen);
            final byte[] plain  = aesCtr(sessionKey, iv, cipher);

            final byte[] out = new byte[RTP_HEADER_LEN + plain.length];
            System.arraycopy(srtpPacket, 0, out, 0, RTP_HEADER_LEN);
            System.arraycopy(plain, 0, out, RTP_HEADER_LEN, plain.length);
            return out;
        } catch (final GeneralSecurityException e) {
            throw new SrtpException("SRTP unprotect failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Private — crypto primitives
    // ------------------------------------------------------------------

    /** Track the 48-bit packet index, bumping the ROC on sequence rollover. */
    private long advanceRoc(final int seq) {
        if (lastSeq >= 0 && seq < lastSeq && (lastSeq - seq) > 0x8000) {
            roc = (roc + 1) & 0xFFFFFFFFL;
        }
        lastSeq = seq;
        return (roc << 16) | (seq & 0xFFFFL);
    }

    /** Build the 128-bit AES-CM IV (RFC 3711 §4.1.1): salt·2^16 ⊕ SSRC·2^64 ⊕ i·2^16. */
    private byte[] encryptionIv(final long ssrc, final long packetIndex) {
        final byte[] iv = new byte[16];
        System.arraycopy(sessionSalt, 0, iv, 0, MASTER_SALT_LEN); // bytes 0..13, 14..15 = 0
        // SSRC (32-bit) at bytes 4..7
        iv[4] ^= (byte) (ssrc >>> 24);
        iv[5] ^= (byte) (ssrc >>> 16);
        iv[6] ^= (byte) (ssrc >>> 8);
        iv[7] ^= (byte) ssrc;
        // 48-bit packet index at bytes 8..13
        iv[8]  ^= (byte) (packetIndex >>> 40);
        iv[9]  ^= (byte) (packetIndex >>> 32);
        iv[10] ^= (byte) (packetIndex >>> 24);
        iv[11] ^= (byte) (packetIndex >>> 16);
        iv[12] ^= (byte) (packetIndex >>> 8);
        iv[13] ^= (byte) packetIndex;
        return iv;
    }

    /** HMAC-SHA1 over (authenticated portion ‖ ROC), truncated to 80 bits. */
    private byte[] authTag(final byte[] packet, final int authedLen, final long packetIndex)
            throws GeneralSecurityException {
        final Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sessionAuthKey, "HmacSHA1"));
        mac.update(packet, 0, authedLen);
        final long currentRoc = packetIndex >>> 16;
        mac.update(new byte[] {
                (byte) (currentRoc >>> 24), (byte) (currentRoc >>> 16),
                (byte) (currentRoc >>> 8),  (byte) currentRoc
        });
        return Arrays.copyOf(mac.doFinal(), AUTH_TAG_LEN);
    }

    /** Session-key derivation PRF (RFC 3711 §4.3): AES-CM(masterKey, (salt ⊕ label)‖0x0000). */
    static byte[] deriveKey(
            final byte[] masterKey, final byte[] masterSalt, final byte label, final int len)
            throws GeneralSecurityException {
        final byte[] iv = new byte[16];
        System.arraycopy(masterSalt, 0, iv, 0, MASTER_SALT_LEN); // bytes 0..13; 14..15 = 0
        iv[7] ^= label; // key_id = label‖r (r = 0); label lands at salt byte 7
        return aesCtr(masterKey, iv, new byte[len]);
    }

    /** AES-128 counter mode: keystream over {@code input.length} bytes XORed with input. */
    private static byte[] aesCtr(final byte[] key, final byte[] iv, final byte[] input)
            throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }

    private static int readUint16(final byte[] b, final int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long readUint32(final byte[] b, final int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
