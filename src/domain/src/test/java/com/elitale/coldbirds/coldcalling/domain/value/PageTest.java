package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class PageTest {

    @Test
    void emptyPageHasNoRowsNoCursorZeroTotal() {
        Page<String> page = Page.empty();
        assertThat(page.rows()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void rowsAreUnmodifiable() {
        Page<String> page = new Page<>(List.of("a", "b"), Optional.empty(), 2);
        assertThatThrownBy(() -> page.rows().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hasNextReflectsCursor() {
        Page<String> page = new Page<>(List.of("a"), Optional.of(new Cursor(1L, 1L)), 10);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void negativeTotalIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Page<>(List.of(), Optional.empty(), -1));
    }

    @Test
    void nullArgsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Page<>(null, Optional.empty(), 0));
        assertThatNullPointerException().isThrownBy(() -> new Page<>(List.of(), null, 0));
    }
}
