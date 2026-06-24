package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsStatusLabelTest {

    @Test
    void label_mapsEachStatus() {
        assertThat(SmsStatusLabel.label(new SmsStatus.Pending())).isEqualTo("Sending\u2026");
        assertThat(SmsStatusLabel.label(new SmsStatus.Delivered())).isEqualTo("Sent");
        assertThat(SmsStatusLabel.label(new SmsStatus.Failed("carrier rejected"))).isEqualTo("Failed");
    }

    @Test
    void styleClass_mapsEachStatus() {
        assertThat(SmsStatusLabel.styleClass(new SmsStatus.Pending())).isEqualTo("sms-status-sending");
        assertThat(SmsStatusLabel.styleClass(new SmsStatus.Delivered())).isEqualTo("sms-status-sent");
        assertThat(SmsStatusLabel.styleClass(new SmsStatus.Failed("x"))).isEqualTo("sms-status-failed");
    }
}
