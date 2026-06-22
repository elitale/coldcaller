package com.elitale.coldbirds.coldcalling.telephony.rtp.srtp;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * SRTP keying material for the {@code AES_CM_128_HMAC_SHA1_80} suite: a 16-byte
 * master key plus a 14-byte master salt, as carried in an SDP {@code a=crypto}
 * line's {@code inline:} parameter (RFC 4568).
 *
 * <p>The inline value is the base64 of {@code masterKey ‖ masterSalt} (30 bytes).
 */
public final class SrtpKey {

    /** SDP crypto suite name this app offers and accepts. */
    public static final String SUITE = "AES_CM_128_HMAC_SHA1_80";

    private static final int KEY_LEN  = 16;
    private static final int SALT_LEN = 14;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;
    private final byte[] masterSalt;

    private SrtpKey(final byte[] masterKey, final byte[] masterSalt) {
        this.masterKey  = masterKey;
        this.masterSalt = masterSalt;
    }

    /** Generate a fresh random key/salt for one call leg. */
    public static SrtpKey random() {
        final byte[] key  = new byte[KEY_LEN];
        final byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(key);
        RANDOM.nextBytes(salt);
        return new SrtpKey(key, salt);
    }

    /**
     * Parse keying material from an SDP crypto {@code inline:} base64 value.
     *
     * @param inlineBase64 base64 of {@code masterKey ‖ masterSalt}; must not be null
     * @return the key if it decodes to exactly 30 bytes, otherwise empty
     */
    public static Optional<SrtpKey> fromInline(final String inlineBase64) {
        Objects.requireNonNull(inlineBase64, "inlineBase64 must not be null");
        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(inlineBase64.strip());
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length != KEY_LEN + SALT_LEN) {
            return Optional.empty();
        }
        final byte[] key  = new byte[KEY_LEN];
        final byte[] salt = new byte[SALT_LEN];
        System.arraycopy(raw, 0, key, 0, KEY_LEN);
        System.arraycopy(raw, KEY_LEN, salt, 0, SALT_LEN);
        return Optional.of(new SrtpKey(key, salt));
    }

    /** The {@code inline:} base64 value for an SDP {@code a=crypto} line. */
    public String toInline() {
        final byte[] raw = new byte[KEY_LEN + SALT_LEN];
        System.arraycopy(masterKey, 0, raw, 0, KEY_LEN);
        System.arraycopy(masterSalt, 0, raw, KEY_LEN, SALT_LEN);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Build the SRTP cipher/auth context for this keying material. */
    public SrtpContext context() throws GeneralSecurityException {
        return SrtpContext.fromMaster(masterKey, masterSalt);
    }
}
