package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.onboarding.ProviderOption;
import com.elitale.coldbirds.coldcalling.domain.onboarding.ProviderOptions;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.services.OnboardingDraft;
import com.elitale.coldbirds.coldcalling.services.OnboardingService;
import com.elitale.coldbirds.coldcalling.telephony.sip.SipCredentials;
import com.elitale.coldbirds.coldcalling.ui.support.OnboardingModel;
import com.elitale.coldbirds.coldcalling.ui.support.OnboardingModel.Step;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Controller for {@code onboarding-view.fxml}.
 *
 * <p>Renders the four wizard steps backed by an {@link OnboardingModel} and
 * drives credential tests through {@link OnboardingService}. All service calls
 * run off the FX Application Thread and re-enter via {@link Platform#runLater}.
 * Step-gating and selection logic live in the (FX-free) model.
 */
public final class OnboardingController {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingController.class);

    @FXML private Label dot1, dot2, dot3, dot4, dot5;
    @FXML private Label name1, name2, name3, name4, name5;
    @FXML private Label statusLabel, countLabel;
    @FXML private VBox step1Pane, step2Pane, step3Pane, step4Pane, step5Pane;
    @FXML private FlowPane providerContainer;
    @FXML private VBox numbersContainer;

    @FXML private TextField     sidField;
    @FXML private PasswordField tokenField;
    @FXML private TextField     sipUsernameField;
    @FXML private PasswordField sipPasswordField;
    @FXML private TextField     sipDomainField;
    @FXML private TextField     sipProxyField;
    @FXML private Spinner<Integer> sipProxyPortSpinner;
    @FXML private TextField     routingUrlField;
    @FXML private TextField     routingCallerIdField;
    @FXML private Button        autoRoutingButton;
    @FXML private Button        applyRoutingButton;
    @FXML private CheckBox      selectAllCheck;

    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button autoSipButton;
    @FXML private Button backButton;
    @FXML private Button primaryButton;

    private final OnboardingModel model = new OnboardingModel();

    private OnboardingService onboardingService;
    private Runnable onComplete = () -> { };
    private boolean busy = false;

    /** Required no-arg constructor for FXMLLoader. */
    public OnboardingController() {}

    public void setOnboardingService(final OnboardingService service) {
        this.onboardingService = Objects.requireNonNull(service, "onboardingService must not be null");
    }

    public void setOnComplete(final Runnable onComplete) {
        this.onComplete = Objects.requireNonNull(onComplete, "onComplete must not be null");
    }

    @FXML
    private void initialize() {
        buildProviderCards();
        sipDomainField.setText("sip.twilio.com");
        sipProxyField.setText("sip.twilio.com");
        sipProxyPortSpinner.setValueFactory(new IntegerSpinnerValueFactory(1, 65535, 5060));

        sidField.textProperty().addListener((o, a, b) -> syncTwilioGate());
        tokenField.textProperty().addListener((o, a, b) -> syncTwilioGate());
        sipUsernameField.textProperty().addListener((o, a, b) -> syncSipGate());
        sipPasswordField.textProperty().addListener((o, a, b) -> syncSipGate());
        selectAllCheck.setOnAction(e -> {
            model.selectAll(selectAllCheck.isSelected());
            renderNumberRows();
            refreshNumbersFooter();
        });

        render();
        prefillFromDraft();
    }

    // ── Footer actions ──────────────────────────────────────────────────────────

    @FXML
    private void onBack() {
        if (busy) {
            return;
        }
        model.back();
        render();
    }

    @FXML
    private void onPrimary() {
        if (busy) {
            return;
        }
        switch (model.current()) {
            case PROVIDER -> { model.next(); render(); }
            case TWILIO   -> testTwilio();
            case SIP      -> testSip();
            case ROUTING  -> { model.next(); render(); }
            case NUMBERS  -> finish();
        }
    }

    // ── Step actions ──────────────────────────────────────────────────────────────

    private void testTwilio() {
        model.setTwilioCredentials(sidField.getText(), tokenField.getText());
        if (!model.canTestTwilio()) {
            return;
        }
        setBusy(true, "Testing credentials…");
        final String sid = sidField.getText();
        final String token = tokenField.getText();
        runAsync(() -> onboardingService.testTwilio(sid, token), result -> {
            if (result instanceof Result.Ok<List<TwilioNumberData>> ok) {
                persistAsync(() -> onboardingService.saveTwilioDraft(sid, token));
                model.setAvailableNumbers(ok.value());
                setStatus("Connected · " + ok.value().size() + " numbers found", false);
                model.next();
                render();
            } else {
                setStatus(message(result, "Authentication failed — check your SID and token."), true);
            }
        });
    }

    private void testSip() {
        final SipCredentials creds;
        try {
            creds = new SipCredentials(
                    sipUsernameField.getText(),
                    sipPasswordField.getText(),
                    sipDomainField.getText(),
                    sipProxyField.getText(),
                    sipProxyPortSpinner.getValue());
        } catch (final RuntimeException e) {
            setStatus("Check your SIP details: " + e.getMessage(), true);
            return;
        }
        setBusy(true, "Registering… (up to 10s)");
        onboardingService.testSip(creds).whenComplete((result, error) -> Platform.runLater(() -> {
            setBusy(false, "");
            if (error == null && result.isOk()) {
                persistAsync(() -> onboardingService.saveSipDraft(creds));
                model.setSip(creds);
                setStatus("Registered", false);
                model.next();
                render();
            } else {
                setStatus(error != null
                        ? "SIP test failed: " + error.getMessage()
                        : message(result, "Registration failed."), true);
            }
        }));
    }

    @FXML
    private void onAutoConfigureSip() {
        if (busy) {
            return;
        }
        final String sid = model.sid();
        final String token = model.token();
        if (sid == null || sid.isBlank() || token == null || token.isBlank()) {
            setStatus("Connect your Twilio account first.", true);
            return;
        }
        setBusy(true, "Setting up SIP on your Twilio account…");
        runAsync(() -> onboardingService.autoConfigureSip(sid, token), result -> {
            if (result instanceof Result.Ok<SipCredentials> ok) {
                applySipCredentials(ok.value());
                setStatus("SIP created · testing…", false);
                testSip();
            } else {
                setStatus(message(result, "Couldn't set up SIP automatically."), true);
            }
        });
    }

    private void applySipCredentials(final SipCredentials creds) {
        sipUsernameField.setText(creds.username());
        sipPasswordField.setText(creds.password());
        sipDomainField.setText(creds.domain());
        sipProxyField.setText(creds.proxyHost());
        sipProxyPortSpinner.getValueFactory().setValue(creds.proxyPort());
    }

    @FXML
    private void onAutoConfigureRouting() {
        if (busy) {
            return;
        }
        setBusy(true, "Setting up call routing…");
        runAsync(() -> onboardingService.autoConfigureRouting(ProviderOptions.TWILIO_ID), result -> {
            if (result instanceof Result.Ok<CallRoutingConfig> ok) {
                model.setRouting(ok.value());
                routingUrlField.setText(ok.value().voiceUrl());
                setStatus("Call routing is ready", false);
            } else {
                setStatus(message(result, "Couldn't set up routing automatically."), true);
            }
        });
    }

    @FXML
    private void onApplyManualRouting() {
        if (busy) {
            return;
        }
        final String url = routingUrlField.getText() == null ? "" : routingUrlField.getText().trim();
        final String callerId = routingCallerIdField.getText() == null ? "" : routingCallerIdField.getText().trim();
        if (url.isBlank()) {
            setStatus("Paste your bridge URL, or use Auto-configure.", true);
            return;
        }
        setBusy(true, "Applying routing…");
        runAsync(() -> onboardingService.applyManualRouting(ProviderOptions.TWILIO_ID, url, callerId), result -> {
            if (result instanceof Result.Ok<CallRoutingConfig> ok) {
                model.setRouting(ok.value());
                setStatus("Call routing saved", false);
            } else {
                setStatus(message(result, "That bridge URL didn't work."), true);
            }
        });
    }

    private void finish() {
        if (!model.canFinish()) {
            return;
        }
        setBusy(true, "Saving…");
        runAsync(() -> onboardingService.completeOnboarding(model.buildResult()), result -> {
            if (result.isOk()) {
                onComplete.run();
            } else {
                setStatus(message(result, "Could not save your setup. Please try again."), true);
            }
        });
    }

    // ── Rendering ───────────────────────────────────────────────────────────────

    private void render() {
        show(step1Pane, model.current() == Step.PROVIDER);
        show(step2Pane, model.current() == Step.TWILIO);
        show(step3Pane, model.current() == Step.SIP);
        show(step4Pane, model.current() == Step.ROUTING);
        show(step5Pane, model.current() == Step.NUMBERS);

        styleStep(dot1, name1, 0);
        styleStep(dot2, name2, 1);
        styleStep(dot3, name3, 2);
        styleStep(dot4, name4, 3);
        styleStep(dot5, name5, 4);

        backButton.setVisible(!model.isFirst());
        backButton.setManaged(!model.isFirst());
        primaryButton.setText(model.isLast() ? "Finish" : "Continue ›");
        setStatus("", false);

        if (model.current() == Step.ROUTING) {
            prepareRoutingStep();
        }
        if (model.current() == Step.NUMBERS) {
            renderNumberRows();
            refreshNumbersFooter();
        }
        updatePrimaryEnabled();
    }

    /** Gate the auto-config button by provider capability and pre-fill any saved bridge URL. */
    private void prepareRoutingStep() {
        autoRoutingButton.setDisable(busy || !onboardingService.supportsAutoRouting(ProviderOptions.TWILIO_ID));
        if (routingUrlField.getText().isBlank() && model.routing().isConfigured()) {
            routingUrlField.setText(model.routing().voiceUrl());
            routingCallerIdField.setText(model.routing().callerIdFallback());
        }
    }

    private void updatePrimaryEnabled() {
        final boolean enabled = switch (model.current()) {
            case PROVIDER -> true;
            case TWILIO   -> model.canTestTwilio() || bothFilled(sidField, tokenField);
            case SIP      -> !sipUsernameField.getText().isBlank() && !sipPasswordField.getText().isBlank();
            case ROUTING  -> true;
            case NUMBERS  -> model.canFinish();
        };
        primaryButton.setDisable(busy || !enabled);
    }

    private void renderNumberRows() {
        numbersContainer.getChildren().clear();
        final List<TwilioNumberData> numbers = model.availableNumbers();
        if (numbers.isEmpty()) {
            final Label empty = new Label("No numbers on this account");
            empty.getStyleClass().add("caption");
            numbersContainer.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < numbers.size(); i++) {
            numbersContainer.getChildren().add(buildNumberRow(i, numbers.get(i)));
        }
    }

    private HBox buildNumberRow(final int index, final TwilioNumberData data) {
        final CheckBox check = new CheckBox();
        check.setSelected(model.isSelected(index));
        check.setOnAction(e -> {
            model.setSelected(index, check.isSelected());
            refreshNumbersFooter();
        });
        final Label number = new Label(data.phoneNumber());
        number.getStyleClass().add("mono");
        final HBox row = new HBox(12, check, number);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("number-row");
        return row;
    }

    private void refreshNumbersFooter() {
        countLabel.setText(model.selectedCount() + " of " + model.availableNumbers().size() + " selected");
        selectAllCheck.setSelected(model.allSelected());
        updatePrimaryEnabled();
    }

    private void buildProviderCards() {
        for (final ProviderOption option : ProviderOptions.ALL) {
            providerContainer.getChildren().add(buildProviderCard(option));
        }
    }

    private VBox buildProviderCard(final ProviderOption option) {
        final Label name = new Label(option.displayName());
        name.getStyleClass().add("title-3");
        final VBox card = new VBox(8, name);
        card.setAlignment(Pos.CENTER);
        card.setMinSize(220, 96);
        card.setPrefSize(220, 96);
        card.setMaxWidth(220);
        card.getStyleClass().add("provider-card");
        if (option.available()) {
            card.getStyleClass().add("selected");
        } else {
            card.getStyleClass().add("disabled");
            final Label badge = new Label("Coming soon");
            badge.getStyleClass().addAll("badge", "warning");
            card.getChildren().add(badge);
        }
        return card;
    }

    private <T> void runAsync(final java.util.function.Supplier<T> work, final java.util.function.Consumer<T> onResult) {
        java.util.concurrent.CompletableFuture.supplyAsync(work)
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    setBusy(false, "");
                    if (error != null) {
                        setStatus("Something went wrong: " + error.getMessage(), true);
                    } else {
                        onResult.accept(value);
                    }
                }));
    }

    /** Load any saved draft off-thread and pre-fill the inputs on the FX thread. */
    private void prefillFromDraft() {
        java.util.concurrent.CompletableFuture
                .supplyAsync(onboardingService::loadDraft)
                .thenAccept(draft -> Platform.runLater(() -> applyDraft(draft)));
    }

    private void applyDraft(final OnboardingDraft draft) {
        sidField.setText(draft.accountSid());
        tokenField.setText(draft.authToken());
        sipUsernameField.setText(draft.sipUsername());
        sipPasswordField.setText(draft.sipPassword());
        sipDomainField.setText(draft.sipDomain());
        sipProxyField.setText(draft.sipProxy());
        sipProxyPortSpinner.getValueFactory().setValue(draft.sipProxyPort());
        updatePrimaryEnabled();
    }

    /** Fire-and-forget persistence of a draft; logs but never blocks the user. */
    private void persistAsync(final Runnable work) {
        java.util.concurrent.CompletableFuture.runAsync(work)
                .exceptionally(error -> {
                    LOG.warn("Failed to save onboarding draft", error);
                    return null;
                });
    }

    private void syncTwilioGate() {
        if (model.current() == Step.TWILIO) {
            updatePrimaryEnabled();
        }
    }

    private void syncSipGate() {
        if (model.current() == Step.SIP) {
            updatePrimaryEnabled();
        }
    }

    private void setBusy(final boolean busy, final String status) {
        this.busy = busy;
        progressIndicator.setVisible(busy);
        if (busy) {
            statusLabel.getStyleClass().removeAll("status-error", "status-ok");
            statusLabel.setText(status);
        }
        sidField.setDisable(busy);
        tokenField.setDisable(busy);
        sipUsernameField.setDisable(busy);
        sipPasswordField.setDisable(busy);
        autoSipButton.setDisable(busy);
        routingUrlField.setDisable(busy);
        routingCallerIdField.setDisable(busy);
        autoRoutingButton.setDisable(busy);
        applyRoutingButton.setDisable(busy);
        backButton.setDisable(busy);
        updatePrimaryEnabled();
    }

    private void setStatus(final String text, final boolean error) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll("status-error", "status-ok");
        if (!text.isBlank()) {
            statusLabel.getStyleClass().add(error ? "status-error" : "status-ok");
        }
    }

    private void styleStep(final Label dot, final Label name, final int stepIndex) {
        dot.getStyleClass().removeAll("active", "done");
        name.getStyleClass().removeAll("active", "done");
        final int current = model.stepNumber() - 1;
        if (stepIndex < current) {
            dot.getStyleClass().add("done");
            dot.setText("\u2713");
            name.getStyleClass().add("done");
        } else if (stepIndex == current) {
            dot.getStyleClass().add("active");
            dot.setText(String.valueOf(stepIndex + 1));
            name.getStyleClass().add("active");
        } else {
            dot.setText(String.valueOf(stepIndex + 1));
        }
    }

    private static void show(final Region pane, final boolean visible) {
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    private static boolean bothFilled(final TextField a, final TextField b) {
        return !a.getText().isBlank() && !b.getText().isBlank();
    }

    private static String message(final Result<?> result, final String fallback) {
        return result instanceof Result.Err<?> err && err.message() != null && !err.message().isBlank()
                ? err.message()
                : fallback;
    }
}
