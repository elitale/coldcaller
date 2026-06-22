package com.elitale.coldbirds.coldcalling.providers.twilio.dto;

import java.util.Objects;

/**
 * Result of auto-provisioning a SIP registration on a Twilio account: the SIP
 * domain to register against plus a freshly-created credential. The password is
 * known only because this client generated it — Twilio never returns it again.
 *
 * @param domainName the {@code *.sip.twilio.com} domain to register to
 * @param username   the SIP credential username
 * @param password   the generated SIP credential password
 */
public record SipProvisioning(String domainName, String username, String password) {

    public SipProvisioning {
        Objects.requireNonNull(domainName, "domainName must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(password, "password must not be null");
        if (domainName.isBlank()) {
            throw new IllegalArgumentException("domainName must not be blank");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
    }
}
