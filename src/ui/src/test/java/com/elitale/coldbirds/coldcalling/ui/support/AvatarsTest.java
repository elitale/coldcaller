package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarsTest {

    @Test
    void initials_firstAndLastName() {
        assertThat(Avatars.initials("Dave Smith")).isEqualTo("DS");
        assertThat(Avatars.initials("dave  van  smith")).isEqualTo("DS");
    }

    @Test
    void initials_singleName() {
        assertThat(Avatars.initials("Dave")).isEqualTo("D");
    }

    @Test
    void initials_numberOrBlank() {
        assertThat(Avatars.initials("+12025551234")).isEqualTo("#");
        assertThat(Avatars.initials("  ")).isEqualTo("?");
        assertThat(Avatars.initials(null)).isEqualTo("?");
    }

    @Test
    void colorIndex_isStableAndInRange() {
        int a = Avatars.colorIndex("Dave Smith");
        int b = Avatars.colorIndex("Dave Smith");
        assertThat(a).isEqualTo(b);
        assertThat(a).isBetween(0, Avatars.paletteSize() - 1);
        assertThat(Avatars.colorIndex(null)).isEqualTo(0);
    }
}
