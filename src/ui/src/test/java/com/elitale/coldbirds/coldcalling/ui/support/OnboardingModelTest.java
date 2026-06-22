package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingMode;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.services.OnboardingResult;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingModelTest {

    private OnboardingModel model;

    private static final SipCredentials SIP =
            new SipCredentials("user", "pass", "sip.twilio.com", "sip.twilio.com", 5060);
    private static final TwilioNumberData N1 = new TwilioNumberData("PN1", "+12025550001", "in-use");
    private static final TwilioNumberData N2 = new TwilioNumberData("PN2", "+12025550002", "in-use");
    private static final TwilioNumberData N3 = new TwilioNumberData("PN3", "+12025550003", "in-use");

    @BeforeEach
    void setUp() {
        model = new OnboardingModel();
    }

    @Test
    void startsOnProviderStep() {
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.PROVIDER);
        assertThat(model.stepNumber()).isEqualTo(1);
        assertThat(model.totalSteps()).isEqualTo(5);
        assertThat(model.isFirst()).isTrue();
        assertThat(model.isLast()).isFalse();
    }

    @Test
    void nextAndBack_navigateLinearly_andClamp() {
        model.next();
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.TWILIO);
        model.next();
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.SIP);
        model.next();
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.ROUTING);
        model.next();
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.NUMBERS);
        assertThat(model.isLast()).isTrue();
        model.next(); // clamps
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.NUMBERS);

        model.back();
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.ROUTING);
        model.back();
        model.back();
        model.back();
        model.back(); // clamps
        assertThat(model.current()).isEqualTo(OnboardingModel.Step.PROVIDER);
    }

    @Test
    void canTestTwilio_requiresBothFields() {
        assertThat(model.canTestTwilio()).isFalse();
        model.setTwilioCredentials("AC123", "");
        assertThat(model.canTestTwilio()).isFalse();
        model.setTwilioCredentials("AC123", "tok");
        assertThat(model.canTestTwilio()).isTrue();
    }

    @Test
    void setAvailableNumbers_clearsSelection() {
        model.setAvailableNumbers(List.of(N1, N2));
        model.selectAll(true);
        assertThat(model.selectedCount()).isEqualTo(2);

        model.setAvailableNumbers(List.of(N1, N2, N3));
        assertThat(model.selectedCount()).isZero();
        assertThat(model.availableNumbers()).containsExactly(N1, N2, N3);
    }

    @Test
    void selection_togglesAndCounts() {
        model.setAvailableNumbers(List.of(N1, N2, N3));
        assertThat(model.canFinish()).isFalse();

        model.setSelected(0, true);
        model.setSelected(2, true);
        assertThat(model.selectedCount()).isEqualTo(2);
        assertThat(model.isSelected(1)).isFalse();
        assertThat(model.allSelected()).isFalse();
        assertThat(model.canFinish()).isTrue();

        model.setSelected(0, false);
        assertThat(model.selectedCount()).isEqualTo(1);
    }

    @Test
    void selectAll_togglesEveryNumber() {
        model.setAvailableNumbers(List.of(N1, N2, N3));
        model.selectAll(true);
        assertThat(model.allSelected()).isTrue();
        assertThat(model.selectedCount()).isEqualTo(3);

        model.selectAll(false);
        assertThat(model.selectedCount()).isZero();
        assertThat(model.allSelected()).isFalse();
    }

    @Test
    void setSelected_outOfRange_throws() {
        model.setAvailableNumbers(List.of(N1));
        assertThatThrownBy(() -> model.setSelected(5, true))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void buildResult_collectsSelectedInOrder() {
        model.setTwilioCredentials("AC123", "tok");
        model.setSip(SIP);
        model.setAvailableNumbers(List.of(N1, N2, N3));
        model.setSelected(2, true);
        model.setSelected(0, true);

        final OnboardingResult result = model.buildResult();

        assertThat(result.accountSid()).isEqualTo("AC123");
        assertThat(result.authToken()).isEqualTo("tok");
        assertThat(result.sip()).isEqualTo(SIP);
        assertThat(result.selectedNumbers()).containsExactly(N1, N3);
        assertThat(result.routing()).isEqualTo(CallRoutingConfig.none("twilio"));
    }

    @Test
    void buildResult_includesRouting_whenSet() {
        model.setTwilioCredentials("AC123", "tok");
        model.setSip(SIP);
        model.setAvailableNumbers(List.of(N1));
        model.setSelected(0, true);
        final CallRoutingConfig routing =
                new CallRoutingConfig("twilio", CallRoutingMode.MANUAL, "https://b.twil.io/x", "");
        model.setRouting(routing);

        assertThat(model.buildResult().routing()).isEqualTo(routing);
    }

    @Test
    void buildResult_withoutSip_throws() {
        model.setAvailableNumbers(List.of(N1));
        model.setSelected(0, true);
        assertThatThrownBy(model::buildResult).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildResult_withoutSelection_throws() {
        model.setSip(SIP);
        model.setAvailableNumbers(List.of(N1));
        assertThatThrownBy(model::buildResult).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hasNumbers_reflectsAvailability() {
        assertThat(model.hasNumbers()).isFalse();
        model.setAvailableNumbers(List.of(N1));
        assertThat(model.hasNumbers()).isTrue();
    }
}
