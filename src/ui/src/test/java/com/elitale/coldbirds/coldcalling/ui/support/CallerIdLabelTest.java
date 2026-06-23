package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CallerIdLabelTest {

    private static OwnedNumber number(final String e164, final Optional<String> friendlyName) {
        return new OwnedNumber(
                new PhoneNumberId(1L),
                new PhoneNumber(e164),
                friendlyName,
                new AreaCode("202"),
                "twilio",
                new NumberReputation.Clean(),
                0,
                true,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    @Test
    void describe_named_showsFriendlyNameAndNumber() {
        assertThat(CallerIdLabel.describe(number("+12025550100", Optional.of("Main Line"))))
                .isEqualTo("Main Line  \u00b7  +12025550100");
    }

    @Test
    void describe_unnamed_showsBareNumber() {
        assertThat(CallerIdLabel.describe(number("+12025550100", Optional.empty())))
                .isEqualTo("+12025550100");
    }

    @Test
    void describe_blankName_fallsBackToNumber() {
        assertThat(CallerIdLabel.describe(number("+12025550100", Optional.of("   "))))
                .isEqualTo("+12025550100");
    }

    @Test
    void describe_null_throws() {
        assertThatNullPointerException().isThrownBy(() -> CallerIdLabel.describe(null));
    }
}
