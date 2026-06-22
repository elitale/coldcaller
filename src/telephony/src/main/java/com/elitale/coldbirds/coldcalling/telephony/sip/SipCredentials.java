package com.elitale.coldbirds.coldcalling.telephony.sip;

import java.util.Objects;

/**
 * SIP registration credentials for a Twilio (or compatible) SIP trunk.
 *
 * <p>Stored via OS keychain — never in SQLite in plaintext.
 *
 * @param username   SIP username (e.g. a Twilio phone number in E.164)
 * @param password   SIP password; kept as a String because JAIN-SIP requires it
 * @param domain     SIP domain / realm (e.g. {@code sip.twilio.com})
 * @param proxyHost  SIP outbound proxy hostname
 * @param proxyPort  SIP outbound proxy port (typically 5060)
 */
public record SipCredentials(
        String username,
        String password,
        String domain,
        String proxyHost,
        int    proxyPort) {

    public SipCredentials {
        Objects.requireNonNull(username,  "username must not be null");
        Objects.requireNonNull(password,  "password must not be null");
        Objects.requireNonNull(domain,    "domain must not be null");
        Objects.requireNonNull(proxyHost, "proxyHost must not be null");
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalArgumentException("proxyPort out of range: " + proxyPort);
        }
    }
}
