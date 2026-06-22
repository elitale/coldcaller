# Plan — Audio Input/Output Device Fix + Device Testing

> Status: PLANNED (locked decisions). Implementation pending.
> Scope: Settings → Audio. Fix invalid device lists, persist + default selection, add
> Google-Meet-style mic/speaker testing, and actually apply the selected devices at call time.

---

## 1. Problems (observed)

1. **Invalid/garbage devices in dropdowns.** `SettingsController.loadAll()` enumerates
   *all* `AudioSystem.getMixerInfo()` and maps to name — includes control-only **Port
   mixers** (`Port C24FG7x`, `Port MacBook Pro Microphone`, `Port Microsoft Teams Audio`…)
   and devices that can't open the call audio format.
2. **No input/output split.** The same list feeds both combos, so speakers appear under
   "Input Device" and mics under "Output Device".
3. **Duplicates.** Same physical device shows twice (`Port X` and `X`).
4. **No "System Default" option**, and default relies on an empty string.
5. **Selection never applied.** `ColdCallingApp` builds `TelephonyService(..., null, null)`,
   so the persisted device is ignored — calls always use the OS default.
6. **No test/verify.** User can't confirm a mic picks up sound or a speaker plays.

---

## 2. Locked Decisions

- **Layering:** All `javax.sound.sampled` access moves OUT of the UI controller into the
  **telephony** layer (it owns audio per AGENTS.md). `ui → telephony` is an allowed dependency.
- **Device identity:** persist the **mixer name** as the stable id. Empty string = "System
  Default" (unchanged storage contract; existing `KEY_AUDIO_INPUT_DEVICE` /
  `KEY_AUDIO_OUTPUT_DEVICE` reused).
- **Filtering rule:**
  - Input list = mixers whose `Mixer` supports a `TargetDataLine` for the call `AudioFormat`.
  - Output list = mixers whose `Mixer` supports a `SourceDataLine` for the call `AudioFormat`.
  - This automatically excludes Port mixers (they expose `Port.class` lines only).
  - **Dedupe by name.** Prepend a single **"System Default"** entry; default-select it.
- **Stale-device fallback:** if a persisted device id is no longer present at load, silently
  fall back to "System Default" and show a subtle hint.
- **Testing model (Google-Meet style):**
  - **Mic test** = live input level meter (RMS → 0..1 bar) driven off-thread; toggle Start/Stop.
  - **Speaker test** = play a short generated tone (no binary asset) through the selected output.
- **Tone:** generated programmatically at the call format (8 kHz / 16-bit / mono signed),
  ~0.7 s, gentle fade in/out. No bundled WAV.
- **Apply without restart:** `TelephonyService` input/output device fields become `volatile`
  with a `setAudioDevices(...)` setter so **Save** affects the *next* call immediately. (SIP
  still needs restart; audio device does not.)
- **Threading:** all line open/read/write on **virtual threads**. Level meter updates via an
  `AnimationTimer` polling a `volatile double` (no `runLater` flooding). Never touch lines on
  the FX thread.

---

## 3. New Code (telephony layer)

Package: `com.elitale.coldbirds.coldcalling.telephony.audio`

### 3.1 `AudioDevice` (record)
```
record AudioDevice(String id, String name, boolean isDefault)
```
- `id` = mixer name; `""` for the system-default sentinel.
- Compact-constructor: `name` non-blank; `id` non-null.
- `static AudioDevice systemDefault()` → `new AudioDevice("", "System Default", true)`.

### 3.2 `AudioDeviceManager`
Responsibilities (no UI, no business logic):
- `List<AudioDevice> inputDevices()` — capability-filtered, deduped, "System Default" first.
- `List<AudioDevice> outputDevices()` — same for output.
- `Optional<Mixer.Info> resolveInput(String id)` / `resolveOutput(String id)` —
  empty Optional = system default (caller passes `null` to the pipeline).
- `boolean exists(String id, Direction dir)` — for stale-device validation.

**Testable core (no hardware):** extract a pure static filter:
```
record MixerEntry(String name, boolean supportsCapture, boolean supportsPlayback)
static List<AudioDevice> filterInputs(List<MixerEntry> entries)
static List<AudioDevice> filterOutputs(List<MixerEntry> entries)
```
The instance methods build `MixerEntry` from real mixers (using
`Mixer.isLineSupported(new DataLine.Info(TargetDataLine|SourceDataLine.class, FORMAT))`),
then delegate to the pure filter. Filter handles dedupe + default-prepend.

### 3.3 `AudioDeviceTester` (Closeable)
- `AutoCloseable startMicMeter(Mixer.Info input, DoubleConsumer onLevel)` —
  opens a `TargetDataLine`, reads 20 ms frames on a virtual thread, computes normalized RMS,
  calls `onLevel`. Returns a handle whose `close()` stops + releases the line.
- `void playTestTone(Mixer.Info output)` — opens a `SourceDataLine`, writes the generated
  tone on a virtual thread, drains + closes. Re-entrant-safe (ignore if already playing).
- **Testable cores (pure):**
  - `static short[] generateTone(int freqHz, int ms)` — length + amplitude asserted.
  - `static double rms(short[] frame)` → 0..1 — silence→0, full-scale→≈1.

---

## 4. Changes to Existing Code

### 4.1 `SettingsController` (UI)
- Remove `javax.sound.sampled.*` imports.
- New injected collaborators: `AudioDeviceManager`, `AudioDeviceTester` (setters, like the
  existing service setters).
- `loadAll()` returns `List<AudioDevice>` for input and output **separately**.
- `applyAll()`:
  - populate `audioInputCombo` from input list, `audioOutputCombo` from output list;
  - select persisted id, or "System Default" if blank/stale.
- Use a `StringConverter<AudioDevice>` (or bind on `name`) so combos show friendly names.
- New handlers:
  - `onToggleMicTest()` — start/stop `startMicMeter`; drive `inputLevelBar` via `AnimationTimer`.
  - `onTestSpeaker()` — `playTestTone(resolvedOutput)`; brief "Playing…" status.
- `onSaveAudio()` — store selected ids (`""` for default) + jitter; then call
  `telephonyService.setAudioDevices(resolvedIn, resolvedOut)` for live apply.
- Add a `dispose()` / on-hidden hook to stop any running mic meter when leaving Settings.

### 4.2 `settings-view.fxml` (Audio card redesign)
```
Audio
  Input Device   [ ComboBox ............ ]  [ Test ]
                 [ ▇▇▇▇▇░░░░░ level bar  ]
  Output Device  [ ComboBox ............ ]  [ Test sound ]
  Jitter Buffer  [ Spinner ]
                                        [ Save ]
```
- Add `inputTestButton`, `inputLevelBar` (ProgressBar), `outputTestButton`.
- Keep 8pt grid, AtlantaFX styles, existing card look.

### 4.3 `ColdCallingApp` (wiring)
- Construct `AudioDeviceManager` + `AudioDeviceTester`.
- Resolve persisted ids → `Mixer.Info` and pass to `TelephonyService` (replace `null, null`).
- Inject `AudioDeviceManager` + `AudioDeviceTester` into `SettingsController`.

### 4.4 `TelephonyService`
- `inputDevice`/`outputDevice`: `final` → `volatile`; add
  `void setAudioDevices(Mixer.Info in, Mixer.Info out)`.
- `startAudio()` reads the current volatile values (already does, just non-final now).

---

## 5. UX Details (simple, forgiving)

- Combos always show a clean, deduped, capability-correct list with "System Default" first.
- **Mic Test:** click → button becomes "Stop", level bar animates live. Auto-stops on Stop,
  device change, Save, or leaving the screen.
- **Speaker Test:** click → plays tone on the chosen output; status shows "Playing test sound…".
- **Empty state:** if no input/output devices, show "No microphone found" / "No speaker found"
  and disable the Test button.
- **Error state:** if a line fails to open, status shows "Couldn't open device — try another."
- Selecting a device does **not** require Save to test (test uses the current combo value).

---

## 6. TDD — Tests First

| Test class | Cases |
|---|---|
| `AudioDeviceTest` | record validation; `systemDefault()` shape |
| `AudioDeviceManagerTest` | `filterInputs`/`filterOutputs`: excludes non-capable, dedupes, prepends one "System Default"; resolve returns empty for blank + unknown id |
| `AudioDeviceTesterTest` | `generateTone` length/amplitude bounds; `rms` silence→0, full-scale→≈1 |

- Hardware-touching methods guarded with `Assumptions.assumeTrue(hasLine(...))` so headless CI
  passes; pure cores fully covered.
- Telephony coverage target ≥ 80% maintained.

---

## 7. Implementation Order (bottom-up)

1. `AudioDevice` record + `AudioDeviceTest` (red → green).
2. `AudioDeviceManager` pure filter + `AudioDeviceManagerTest`; then real-mixer methods.
3. `AudioDeviceTester` pure cores + test; then line I/O methods.
4. `TelephonyService` volatile devices + `setAudioDevices`.
5. `ColdCallingApp` wiring (resolve ids, inject collaborators).
6. `SettingsController` rewrite (lists split, test handlers, dispose hook).
7. `settings-view.fxml` Audio card redesign.
8. `./gradlew build` + `./gradlew test` green.

---

## 8. Deliberately NOT Doing (YAGNI)

- Live USB hot-plug listener — re-enumerate on screen open + validate stored id instead.
- Per-device volume sliders / gain control — not requested.
- Codec / sample-rate selection — fixed to G.711 PCMU 8 kHz by spec.
- Bundled WAV asset — tone is generated.
