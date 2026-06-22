package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.onboarding.ProviderOptions;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.services.OnboardingResult;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Pure, FX-free state machine backing the onboarding wizard.
 *
 * <p>Holds the wizard's step position, the credentials entered so far, the
 * numbers fetched from Twilio, and the user's number selection. The
 * {@code OnboardingController} renders this model and feeds user input back
 * into it; all gating logic lives here so it can be unit-tested without the
 * JavaFX toolkit.
 */
public final class OnboardingModel {

    /** Linear wizard steps in order. */
    public enum Step { PROVIDER, TWILIO, SIP, ROUTING, NUMBERS }

    private static final Step[] STEPS = Step.values();

    private int index = 0;

    private String sid = "";
    private String token = "";
    private SipCredentials sip;
    private CallRoutingConfig routing = CallRoutingConfig.none(ProviderOptions.TWILIO_ID);

    private List<TwilioNumberData> available = List.of();
    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();

    // ── Step navigation ─────────────────────────────────────────────────────────

    public Step current() {
        return STEPS[index];
    }

    public int stepNumber() {
        return index + 1;
    }

    public int totalSteps() {
        return STEPS.length;
    }

    public boolean isFirst() {
        return index == 0;
    }

    public boolean isLast() {
        return index == STEPS.length - 1;
    }

    public void next() {
        if (index < STEPS.length - 1) {
            index++;
        }
    }

    public void back() {
        if (index > 0) {
            index--;
        }
    }

    // ── Twilio step ─────────────────────────────────────────────────────────────

    public void setTwilioCredentials(final String sid, final String token) {
        this.sid = Objects.requireNonNullElse(sid, "");
        this.token = Objects.requireNonNullElse(token, "");
    }

    public String sid() {
        return sid;
    }

    public String token() {
        return token;
    }

    /** Whether the Twilio test can run (both fields filled). */
    public boolean canTestTwilio() {
        return !sid.isBlank() && !token.isBlank();
    }

    // ── SIP step ────────────────────────────────────────────────────────────────

    public void setSip(final SipCredentials sip) {
        this.sip = Objects.requireNonNull(sip, "sip must not be null");
    }

    public SipCredentials sip() {
        return sip;
    }

    // ── Routing step ───────────────────────────────────────────────────

    /** Record the chosen routing config (auto, manual, or {@link CallRoutingConfig#none} when skipped). */
    public void setRouting(final CallRoutingConfig routing) {
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
    }

    public CallRoutingConfig routing() {
        return routing;
    }

    // ── Numbers step ────────────────────────────────────────────────────────────

    /** Replace the fetched numbers; clears any prior selection. */
    public void setAvailableNumbers(final List<TwilioNumberData> numbers) {
        this.available = List.copyOf(Objects.requireNonNull(numbers, "numbers must not be null"));
        this.selected.clear();
    }

    public List<TwilioNumberData> availableNumbers() {
        return available;
    }

    public boolean hasNumbers() {
        return !available.isEmpty();
    }

    public void setSelected(final int index, final boolean on) {
        if (index < 0 || index >= available.size()) {
            throw new IndexOutOfBoundsException("number index out of range: " + index);
        }
        if (on) {
            selected.add(index);
        } else {
            selected.remove(index);
        }
    }

    public boolean isSelected(final int index) {
        return selected.contains(index);
    }

    public void selectAll(final boolean on) {
        selected.clear();
        if (on) {
            for (int i = 0; i < available.size(); i++) {
                selected.add(i);
            }
        }
    }

    public int selectedCount() {
        return selected.size();
    }

    public boolean allSelected() {
        return !available.isEmpty() && selected.size() == available.size();
    }

    /** Finish is allowed once at least one number is selected. */
    public boolean canFinish() {
        return selectedCount() >= 1;
    }

    // ── Result ──────────────────────────────────────────────────────────────────

    /**
     * Build the immutable onboarding result from the current selection.
     *
     * @throws IllegalStateException if SIP credentials or a number selection are missing
     */
    public OnboardingResult buildResult() {
        if (sip == null) {
            throw new IllegalStateException("SIP credentials not set");
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException("no numbers selected");
        }
        final List<TwilioNumberData> chosen = selected.stream()
                .sorted()
                .map(available::get)
                .toList();
        return new OnboardingResult(sid, token, sip, chosen, routing);
    }
}
