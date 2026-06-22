package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;

import java.util.List;
import java.util.Objects;

/**
 * Immutable outcome of the onboarding wizard, ready to be persisted.
 *
 * @param accountSid      validated Twilio Account SID
 * @param authToken       validated Twilio Auth Token
 * @param sip             validated SIP credentials
 * @param selectedNumbers the numbers the user chose to import
 * @param routing         outbound call-routing config ({@link CallRoutingConfig#none} when skipped)
 */
public record OnboardingResult(
        String accountSid,
        String authToken,
        SipCredentials sip,
        List<TwilioNumberData> selectedNumbers,
        CallRoutingConfig routing) {

    public OnboardingResult {
        Objects.requireNonNull(accountSid, "accountSid must not be null");
        Objects.requireNonNull(authToken, "authToken must not be null");
        Objects.requireNonNull(sip, "sip must not be null");
        Objects.requireNonNull(selectedNumbers, "selectedNumbers must not be null");
        Objects.requireNonNull(routing, "routing must not be null");
        selectedNumbers = List.copyOf(selectedNumbers);
    }
}
