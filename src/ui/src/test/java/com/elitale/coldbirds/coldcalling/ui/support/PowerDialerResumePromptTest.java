package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PowerDialerResumePromptTest {

    @Test
    void namesLeadAndPosition() {
        assertThat(PowerDialerResumePrompt.message("Maria Lopez", 46, 74))
                .isEqualTo("Resume with Maria Lopez \u2014 47 of 120?");
    }

    @Test
    void blankNameFallsBackToGenericLabel() {
        assertThat(PowerDialerResumePrompt.message("   ", 0, 10))
                .isEqualTo("Resume power dialer \u2014 1 of 10?");
    }

    @Test
    void nullNameFallsBackToGenericLabel() {
        assertThat(PowerDialerResumePrompt.message(null, 4, 6))
                .isEqualTo("Resume power dialer \u2014 5 of 10?");
    }

    @Test
    void clampsNegativeAndUndersizedInputs() {
        // remaining smaller than expected must never make "current" exceed "total"
        assertThat(PowerDialerResumePrompt.message("Sam", 5, 0))
                .isEqualTo("Resume with Sam \u2014 6 of 6?");
    }
}
