package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioConfig;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipTester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Orchestrates the first-run onboarding wizard: validating provider
 * credentials, testing SIP registration, and persisting the final selection.
 *
 * <p>Credential-dependent clients (the {@link TwilioClient}) are created
 * on-demand via a factory so the wizard can test arbitrary credentials before
 * any are persisted, decoupling it from the application's long-lived singletons.
 */
public final class OnboardingService {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingService.class);

    private static final int DEFAULT_SIP_PORT = 5060;

    private final SettingsService settings;
    private final PhoneNumberService phoneNumbers;
    private final SipTester sipTester;
    private final Function<TwilioConfig, TwilioClient> twilioFactory;

    /** Production constructor — builds real Twilio clients on demand. */
    public OnboardingService(
            final SettingsService settings,
            final PhoneNumberService phoneNumbers,
            final SipTester sipTester) {
        this(settings, phoneNumbers, sipTester, TwilioClient::new);
    }

    /** Test constructor — inject the Twilio client factory. */
    public OnboardingService(
            final SettingsService settings,
            final PhoneNumberService phoneNumbers,
            final SipTester sipTester,
            final Function<TwilioConfig, TwilioClient> twilioFactory) {
        this.settings      = Objects.requireNonNull(settings, "settings must not be null");
        this.phoneNumbers  = Objects.requireNonNull(phoneNumbers, "phoneNumbers must not be null");
        this.sipTester     = Objects.requireNonNull(sipTester, "sipTester must not be null");
        this.twilioFactory = Objects.requireNonNull(twilioFactory, "twilioFactory must not be null");
    }

    /** Whether onboarding has already been completed. */
    public boolean isOnboardingComplete() {
        return settings.isOnboardingComplete();
    }

    /** Load any partially-entered values so the wizard can resume pre-filled. */
    public OnboardingDraft loadDraft() {
        return new OnboardingDraft(
                settings.getTwilioAccountSid(),
                settings.getTwilioAuthToken(),
                settings.getSipUsername(),
                settings.getSipPassword(),
                settings.getSipDomain(),
                settings.getSipProxy(),
                settings.getSipProxyPort());
    }

    /** Persist Twilio credentials as a draft (no completion flag is set). */
    public void saveTwilioDraft(final String accountSid, final String authToken) {
        settings.setTwilioAccountSid(accountSid == null ? "" : accountSid.trim());
        settings.setTwilioAuthToken(authToken == null ? "" : authToken.trim());
    }

    /** Persist SIP credentials as a draft (no completion flag is set). */
    public void saveSipDraft(final SipCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        settings.setSipUsername(credentials.username());
        settings.setSipPassword(credentials.password());
        settings.setSipDomain(credentials.domain());
        settings.setSipProxy(credentials.proxyHost());
        settings.setSipProxyPort(credentials.proxyPort());
    }

    /**
     * Validate Twilio credentials by listing the account's phone numbers.
     *
     * @return {@link Result.Ok} with the account's numbers, or {@link Result.Err}
     *         when credentials are blank or rejected
     */
    public Result<List<TwilioNumberData>> testTwilio(final String accountSid, final String authToken) {
        if (isBlank(accountSid) || isBlank(authToken)) {
            return Result.err("Enter both the Account SID and Auth Token.");
        }
        final TwilioClient client = twilioFactory.apply(TwilioConfig.of(accountSid.trim(), authToken.trim()));
        return client.listPhoneNumbers();
    }

    /**
     * Validate SIP credentials with a one-shot REGISTER.
     */
    public CompletableFuture<Result<Void>> testSip(final SipCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        return sipTester.test(credentials);
    }

    /**
     * Auto-provision a SIP registration on the user's Twilio account and return
     * ready-to-use {@link SipCredentials}. Creates a SIP domain (reusing an
     * existing registration-enabled one when present) and a fresh credential.
     *
     * @return {@link Result.Ok} with usable SIP credentials, or {@link Result.Err}
     *         when the Twilio credentials are blank or provisioning fails
     */
    public Result<SipCredentials> autoConfigureSip(final String accountSid, final String authToken) {
        if (isBlank(accountSid) || isBlank(authToken)) {
            return Result.err("Connect your Twilio account first.");
        }
        final TwilioClient client = twilioFactory.apply(TwilioConfig.of(accountSid.trim(), authToken.trim()));
        final Result<SipProvisioning> provisioned = client.autoProvisionSip();
        return switch (provisioned) {
            case Result.Ok<SipProvisioning> ok -> {
                final SipProvisioning p = ok.value();
                yield Result.ok(new SipCredentials(
                        p.username(), p.password(), p.domainName(), p.domainName(), DEFAULT_SIP_PORT));
            }
            case Result.Err<SipProvisioning> err -> Result.err(err.message(), err.cause());
        };
    }

    /**
     * Persist the onboarding outcome. Phone numbers are written first, the
     * default number is set, and the completion flag is set <em>last</em> so an
     * interrupted run re-shows the wizard rather than booting half-configured.
     *
     * @return the number of newly imported phone numbers
     */
    public Result<Integer> completeOnboarding(final OnboardingResult result) {
        Objects.requireNonNull(result, "result must not be null");

        settings.setTwilioAccountSid(result.accountSid().trim());
        settings.setTwilioAuthToken(result.authToken().trim());

        final SipCredentials sip = result.sip();
        settings.setSipUsername(sip.username());
        settings.setSipPassword(sip.password());
        settings.setSipDomain(sip.domain());
        settings.setSipProxy(sip.proxyHost());
        settings.setSipProxyPort(sip.proxyPort());

        final Result<Integer> saved = phoneNumbers.saveSelected(result.selectedNumbers());
        if (saved instanceof Result.Err<Integer> err) {
            LOG.warn("Onboarding number import failed: {}", err.message());
            return saved;
        }

        setDefaultNumber(result.selectedNumbers());

        settings.setOnboardingComplete(true);
        LOG.info("Onboarding complete: {} number(s) imported", ((Result.Ok<Integer>) saved).value());
        return saved;
    }

    private void setDefaultNumber(final List<TwilioNumberData> numbers) {
        for (final TwilioNumberData data : numbers) {
            try {
                phoneNumbers.setDefault(new PhoneNumber(data.phoneNumber()));
                return;
            } catch (final IllegalArgumentException e) {
                LOG.debug("Skipping invalid number for default: {}", data.phoneNumber());
            }
        }
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
