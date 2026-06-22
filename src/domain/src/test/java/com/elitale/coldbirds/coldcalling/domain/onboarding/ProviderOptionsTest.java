package com.elitale.coldbirds.coldcalling.domain.onboarding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderOptionsTest {

    @Test
    void twilioIsPresentAndAvailable() {
        ProviderOption twilio = ProviderOptions.ALL.stream()
                .filter(p -> p.id().equals(ProviderOptions.TWILIO_ID))
                .findFirst()
                .orElseThrow();
        assertThat(twilio.available()).isTrue();
        assertThat(twilio.displayName()).isEqualTo("Twilio");
    }

    @Test
    void onlyTwilioIsAvailable() {
        assertThat(ProviderOptions.ALL)
                .filteredOn(ProviderOption::available)
                .extracting(ProviderOption::id)
                .containsExactly(ProviderOptions.TWILIO_ID);
    }

    @Test
    void otherProvidersAreComingSoon() {
        assertThat(ProviderOptions.ALL)
                .filteredOn(p -> !p.available())
                .extracting(ProviderOption::id)
                .contains("vonage", "telnyx", "plivo", "bandwidth");
    }

    @Test
    void catalogIsUnmodifiable() {
        assertThatThrownBy(() -> ProviderOptions.ALL.add(new ProviderOption("x", "X", true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankFieldsRejected() {
        assertThatThrownBy(() -> new ProviderOption("", "X", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderOption("x", " ", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
