package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;

import java.util.Objects;

/**
 * Persisted partial onboarding state, used to pre-fill the wizard so a user who
 * closes the app mid-setup does not have to re-enter their credentials.
 *
 * <p>All fields default to empty strings (or the SIP defaults) when nothing has
 * been saved yet — they are never {@code null}.
 */
public record OnboardingDraft(
        String accountSid,
        String authToken,
        String sipUsername,
        String sipPassword,
        String sipDomain,
        String sipProxy,
        int    sipProxyPort) {

    public OnboardingDraft {
        Objects.requireNonNull(accountSid, "accountSid must not be null");
        Objects.requireNonNull(authToken, "authToken must not be null");
        Objects.requireNonNull(sipUsername, "sipUsername must not be null");
        Objects.requireNonNull(sipPassword, "sipPassword must not be null");
        Objects.requireNonNull(sipDomain, "sipDomain must not be null");
        Objects.requireNonNull(sipProxy, "sipProxy must not be null");
    }

    /** Whether a SIP connection was already drafted (username + password present). */
    public boolean hasSip() {
        return !sipUsername.isBlank() && !sipPassword.isBlank();
    }
}
