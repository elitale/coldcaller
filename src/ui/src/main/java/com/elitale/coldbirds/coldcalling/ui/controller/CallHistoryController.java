package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.CallService;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.ui.support.CallHistoryFilterState;
import com.elitale.coldbirds.coldcalling.ui.support.CallHistoryFilterState.Preset;
import com.elitale.coldbirds.coldcalling.ui.support.CallHistoryRollup;
import com.elitale.coldbirds.coldcalling.ui.support.CallHistoryRow;
import com.elitale.coldbirds.coldcalling.ui.support.CallRowCell;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackBuckets;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Call History — lead-aware per-prospect rows, a pinned Callbacks band, search + outcome filters.
 * The rollup / outcome / filter logic lives in tested {@code ui.support} models; this controller
 * wires them to JavaFX.
 *
 * <p>Threading: all I/O runs off the FX thread via {@link CompletableFuture} →
 * {@link Platform#runLater}.
 */
public final class CallHistoryController {

    private static final int MAX_ROWS = 500;
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(200);

    @FXML private TextField    searchField;
    @FXML private ToggleButton allBtn;
    @FXML private ToggleButton inboundBtn;
    @FXML private ToggleButton outboundBtn;
    @FXML private ToggleButton chipCallbacks;
    @FXML private ToggleButton chipNoAnswer;
    @FXML private ToggleButton chipInterested;
    @FXML private ToggleButton chipVoicemail;
    @FXML private ToggleButton chipDnc;
    @FXML private VBox         bandHost;
    @FXML private ListView<CallHistoryRow> list;
    @FXML private Label        countLabel;

    private CallService        callService;
    private LeadService        leadService;
    private PhoneNumberService phoneNumberService;
    private Consumer<String>   onDial = ignored -> {};
    private Consumer<String>   onOpenDetail = ignored -> {};

    private final ZoneId localZone = ZoneId.systemDefault();
    private final CallHistoryFilterState filterState = new CallHistoryFilterState();
    private final ObservableList<CallHistoryRow> rows = FXCollections.observableArrayList();
    private final PauseTransition searchDebounce = new PauseTransition(SEARCH_DEBOUNCE);
    private CallDirection filterDirection = null;
    private boolean showDialedFrom = false;
    private List<CallHistoryRow> allRows = List.of();
    private CallbackBand band;

    /** Default no-arg constructor — required by FXMLLoader. */
    public CallHistoryController() {}

    public void setCallService(CallService service) {
        this.callService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setLeadService(LeadService service) {
        this.leadService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setPhoneNumberService(PhoneNumberService service) {
        this.phoneNumberService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setOnDial(Consumer<String> callback) {
        this.onDial = Objects.requireNonNull(callback, "callback must not be null");
    }

    public void setOnOpenDetail(Consumer<String> callback) {
        this.onOpenDetail = Objects.requireNonNull(callback, "callback must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        list.setItems(rows);
        list.setCellFactory(v -> new CallRowCell(localZone, showDialedFrom, onDial, onOpenDetail));
        Label placeholder = new Label("No call history yet.");
        placeholder.getStyleClass().add("caption");
        list.setPlaceholder(placeholder);
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) openSelected(); });
        list.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) openSelected(); });

        searchField.textProperty().addListener((o, a, b) -> {
            searchDebounce.setOnFinished(e -> { filterState.setSearch(b); applyFilter(); });
            searchDebounce.playFromStart();
        });

        ToggleGroup directions = new ToggleGroup();
        allBtn.setToggleGroup(directions);
        inboundBtn.setToggleGroup(directions);
        outboundBtn.setToggleGroup(directions);
        allBtn.setSelected(true);
        directions.selectedToggleProperty().addListener((o, a, n) -> {
            filterDirection = n == inboundBtn ? CallDirection.INBOUND
                    : n == outboundBtn ? CallDirection.OUTBOUND : null;
            load();
        });

        wireChip(chipCallbacks, Preset.CALLBACKS);
        wireChip(chipNoAnswer, Preset.NO_ANSWER);
        wireChip(chipInterested, Preset.INTERESTED);
        wireChip(chipVoicemail, Preset.VOICEMAIL);
        wireChip(chipDnc, Preset.DNC);

        band = new CallbackBand(bandActions(), localZone);
        bandHost.getChildren().add(band.node());

        load();
    }

    @FXML
    private void onRefresh() {
        load();
    }

    /** Reload the screen (e.g. after an external add). Safe to call on the FX thread. */
    public void refresh() {
        load();
    }

    private void wireChip(ToggleButton chip, Preset preset) {
        chip.selectedProperty().addListener((o, a, on) -> { filterState.togglePreset(preset, on); applyFilter(); });
    }

    private void openSelected() {
        CallHistoryRow selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            onOpenDetail.accept(selected.number());
        }
    }

    private CallbackBand.Actions bandActions() {
        return new CallbackBand.Actions() {
            @Override public void call(String number) { onDial.accept(number); }
            @Override public void open(String number) { onOpenDetail.accept(number); }
            @Override public void reschedule(String number, Instant when) {
                mutate(() -> callService.reschedule(new PhoneNumber(number), when));
            }
            @Override public void resolve(String number) {
                mutate(() -> callService.resolveCallback(new PhoneNumber(number)));
            }
        };
    }

    private void mutate(Runnable serviceCall) {
        CompletableFuture.runAsync(serviceCall).thenRunAsync(this::load, Platform::runLater);
    }

    // ── Loading (off the FX thread) ────────────────────────────────────────────

    private void load() {
        if (callService == null) {
            return;
        }
        CompletableFuture.supplyAsync(this::buildModel)
                .thenAcceptAsync(model -> {
                    showDialedFrom = model.multiNumber();
                    allRows = model.rows();
                    band.setMissedInbound(model.missed());
                    band.setItems(model.callbacks());
                    applyFilter();
                }, Platform::runLater);
    }

    private record Model(List<CallHistoryRow> rows, List<CallbackBuckets.Item> callbacks,
                         List<CallbackBand.MissedItem> missed, boolean multiNumber) {}

    private Model buildModel() {
        final List<Call> recent = callService.findRecent(MAX_ROWS);
        final List<Call> directionFiltered = filterDirection == null ? recent
                : recent.stream().filter(c -> c.direction() == filterDirection).toList();

        final Map<String, Call> newestByNumber = new LinkedHashMap<>();
        for (Call c : directionFiltered) {
            newestByNumber.putIfAbsent(c.remoteNumber().value(), c);
        }

        final List<OwnedNumber> owned = phoneNumberService == null ? List.of() : phoneNumberService.listAll();
        final Map<Long, String> ownedById = new LinkedHashMap<>();
        for (OwnedNumber n : owned) {
            ownedById.put(n.id().value(), n.friendlyName().filter(s -> !s.isBlank()).orElse(n.number().value()));
        }

        final List<CallHistoryRow> built = new ArrayList<>();
        for (CallHistoryRollup.Summary s : CallHistoryRollup.rollup(directionFiltered)) {
            final PhoneNumber phone = new PhoneNumber(s.number());
            final Optional<Lead> lead = leadService == null ? Optional.empty() : leadService.findByPhone(phone);
            final Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, s.number());
            final Optional<String> dialedFrom = Optional.ofNullable(newestByNumber.get(s.number()))
                    .map(c -> ownedById.get(c.phoneNumberId().value()));
            built.add(new CallHistoryRow(s, lead, country, dialedFrom));
        }

        final List<CallbackBuckets.Item> callbacks = new ArrayList<>();
        for (CallService.CallbackEntry e : callService.callbacksDue()) {
            final Optional<Lead> lead = leadService == null ? Optional.empty() : leadService.findByPhone(e.number());
            final Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, e.number().value());
            callbacks.add(new CallbackBuckets.Item(e.number().value(), lead, country, e.scheduledAt()));
        }

        final List<CallbackBand.MissedItem> missed = new ArrayList<>();
        for (Call c : callService.missedInbound()) {
            final Optional<Lead> lead = leadService == null ? Optional.empty() : leadService.findByPhone(c.remoteNumber());
            final Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, c.remoteNumber().value());
            missed.add(new CallbackBand.MissedItem(c.remoteNumber().value(), lead, country, c.startedAt()));
        }
        return new Model(built, callbacks, missed, owned.size() > 1);
    }

    private void applyFilter() {
        final List<CallHistoryRow> visible = allRows.stream().filter(this::passes).toList();
        rows.setAll(visible);
        countLabel.setText(visible.isEmpty() ? ""
                : visible.size() + (visible.size() == 1 ? " prospect" : " prospects"));
    }

    private boolean passes(CallHistoryRow row) {
        return filterState.matches(
                row.number(),
                row.lead().map(Lead::displayName),
                row.company(),
                row.summary().lastOutcome(),
                row.summary().badgeDisposition(),
                row.summary().containsDnc(),
                row.summary().callbackDueAt().isPresent());
    }
}
