package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MotionTest {

    @AfterEach
    void reset() {
        Motion.setReduced(false);
    }

    @Test
    void defaultsToNotReduced() {
        assertThat(Motion.isReduced()).isFalse();
    }

    @Test
    void setReduced_togglesFlag() {
        Motion.setReduced(true);
        assertThat(Motion.isReduced()).isTrue();
        Motion.setReduced(false);
        assertThat(Motion.isReduced()).isFalse();
    }

    @Test
    void pressFlash_nullNode_isNoOp() {
        assertThatCode(() -> Motion.pressFlash(null)).doesNotThrowAnyException();
    }

    @Test
    void pressFlash_whenReduced_isNoOpEvenWithNullNode() {
        Motion.setReduced(true);
        assertThatCode(() -> Motion.pressFlash(null)).doesNotThrowAnyException();
    }
}
