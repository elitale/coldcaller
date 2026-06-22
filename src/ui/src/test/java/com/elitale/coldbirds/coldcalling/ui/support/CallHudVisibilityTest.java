package com.elitale.coldbirds.coldcalling.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CallHudVisibilityTest {

    @Test
    void shows_whenCallLiveAndMainUnfocused() {
        assertThat(CallHudVisibility.shouldShow(true, false)).isTrue();
    }

    @Test
    void hides_whenCallLiveButMainFocused() {
        assertThat(CallHudVisibility.shouldShow(true, true)).isFalse();
    }

    @Test
    void hides_whenNoCallAndMainUnfocused() {
        assertThat(CallHudVisibility.shouldShow(false, false)).isFalse();
    }

    @Test
    void hides_whenNoCallAndMainFocused() {
        assertThat(CallHudVisibility.shouldShow(false, true)).isFalse();
    }
}
