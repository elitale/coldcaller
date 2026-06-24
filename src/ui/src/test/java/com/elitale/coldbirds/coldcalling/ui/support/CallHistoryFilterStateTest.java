package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.ui.support.CallHistoryFilterState.Preset;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class CallHistoryFilterStateTest {

    private static final Optional<CallDisposition> INTERESTED = Optional.of(new CallDisposition.Interested());
    private static final Optional<CallDisposition> NONE = Optional.empty();

    private boolean match(CallHistoryFilterState f, String number, Optional<String> name,
                          CallOutcome outcome, Optional<CallDisposition> badge,
                          boolean dnc, boolean hasCallback) {
        return f.matches(number, name, Optional.empty(), outcome, badge, dnc, hasCallback);
    }

    @Test
    void emptyFilterMatchesEverything() {
        CallHistoryFilterState f = new CallHistoryFilterState();
        assertThat(f.isActive()).isFalse();
        assertThat(match(f, "+1415", Optional.empty(), CallOutcome.NO_ANSWER, NONE, false, false)).isTrue();
    }

    @Test
    void searchMatchesNumberNameOrCompany() {
        CallHistoryFilterState f = new CallHistoryFilterState();
        f.setSearch("jane");
        assertThat(match(f, "+1415", Optional.of("Jane Doe"), CallOutcome.CONNECTED, NONE, false, false)).isTrue();
        assertThat(match(f, "+1415", Optional.of("Bob"), CallOutcome.CONNECTED, NONE, false, false)).isFalse();
        f.setSearch("415");
        assertThat(match(f, "+1415", Optional.empty(), CallOutcome.CONNECTED, NONE, false, false)).isTrue();
    }

    @Test
    void callbacksPresetMatchesRowsWithACallback() {
        CallHistoryFilterState f = new CallHistoryFilterState();
        f.togglePreset(Preset.CALLBACKS, true);
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.CONNECTED, NONE, false, true)).isTrue();
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.CONNECTED, NONE, false, false)).isFalse();
    }

    @Test
    void outcomeAndDncPresets() {
        CallHistoryFilterState f = new CallHistoryFilterState();
        f.togglePreset(Preset.NO_ANSWER, true);
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.NO_ANSWER, NONE, false, false)).isTrue();
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.CONNECTED, NONE, false, false)).isFalse();

        CallHistoryFilterState dncFilter = new CallHistoryFilterState();
        dncFilter.togglePreset(Preset.DNC, true);
        assertThat(match(dncFilter, "+1", Optional.empty(), CallOutcome.CONNECTED, NONE, true, false)).isTrue();
    }

    @Test
    void interestedPresetUsesBadge() {
        CallHistoryFilterState f = new CallHistoryFilterState();
        f.togglePreset(Preset.INTERESTED, true);
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.NO_ANSWER, INTERESTED, false, false)).isTrue();
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.NO_ANSWER, NONE, false, false)).isFalse();
    }

    @Test
    void presetsOrTogetherAndTogglingOff() {
        CallHistoryFilterState f = new CallHistoryFilterState();
        f.togglePreset(Preset.NO_ANSWER, true);
        f.togglePreset(Preset.VOICEMAIL, true);
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.VOICEMAIL, NONE, false, false)).isTrue();
        f.togglePreset(Preset.VOICEMAIL, false);
        assertThat(match(f, "+1", Optional.empty(), CallOutcome.VOICEMAIL, NONE, false, false)).isFalse();
        assertThat(f.presets()).containsExactly(Preset.NO_ANSWER);
    }
}
