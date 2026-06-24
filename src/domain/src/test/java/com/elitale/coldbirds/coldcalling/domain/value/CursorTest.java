package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CursorTest {

    @Test
    void formatThenParseRoundTrips() {
        Cursor cursor = new Cursor(1_700_000_000_000L, 42L);
        assertThat(cursor.format()).isEqualTo("1700000000000_42");
        assertThat(Cursor.parse(cursor.format())).contains(cursor);
    }

    @Test
    void parseRejectsNullBlankAndMalformed() {
        assertThat(Cursor.parse(null)).isEmpty();
        assertThat(Cursor.parse("")).isEmpty();
        assertThat(Cursor.parse("   ")).isEmpty();
        assertThat(Cursor.parse("abc")).isEmpty();
        assertThat(Cursor.parse("123")).isEmpty();   // no separator
        assertThat(Cursor.parse("123_")).isEmpty();  // missing id
        assertThat(Cursor.parse("_42")).isEmpty();   // missing ms
        assertThat(Cursor.parse("12x_3")).isEmpty(); // non-numeric ms
        assertThat(Cursor.parse("12_3x")).isEmpty(); // non-numeric id
    }

    @Test
    void parseRejectsNonPositiveId() {
        assertThat(Cursor.parse("100_0")).isEmpty();
        assertThat(Cursor.parse("100_-5")).isEmpty();
    }

    @Test
    void constructorRejectsNonPositiveId() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cursor(1L, 0L));
        assertThatIllegalArgumentException().isThrownBy(() -> new Cursor(1L, -1L));
    }
}
