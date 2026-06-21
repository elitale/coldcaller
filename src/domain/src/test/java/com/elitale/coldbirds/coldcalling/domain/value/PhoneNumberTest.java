package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhoneNumberTest {

    @Test
    void validE164IsAccepted() {
        assertThatNoException().isThrownBy(() -> new PhoneNumber("+14155552671"));
        assertThatNoException().isThrownBy(() -> new PhoneNumber("+442071838750"));
        assertThatNoException().isThrownBy(() -> new PhoneNumber("+919876543210"));
    }

    @Test
    void missingPlusSignIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PhoneNumber("14155552671"))
                .withMessageContaining("E.164");
    }

    @Test
    void tooShortIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PhoneNumber("+1"));
    }

    @Test
    void tooLongIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PhoneNumber("+123456789012345678"));
    }

    @Test
    void nullIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PhoneNumber(null))
                .withMessageContaining("null");
    }

    @Test
    void toStringReturnsValue() {
        PhoneNumber number = new PhoneNumber("+14155552671");
        assertThat(number.toString()).isEqualTo("+14155552671");
    }

    @Test
    void equalityIsValueBased() {
        assertThat(new PhoneNumber("+14155552671")).isEqualTo(new PhoneNumber("+14155552671"));
        assertThat(new PhoneNumber("+14155552671")).isNotEqualTo(new PhoneNumber("+14155552672"));
    }
}
