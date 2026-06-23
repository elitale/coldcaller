# Calling Screen — Plan

> Status: **Proposed — awaiting approval.** Trigger: clicking **Call** (from the dialer, the recent-call side panel, or the power dialer) opens a dedicated **Calling Screen** with in-call controls — Mute, Keypad (DTMF), Hold, Hang up, Voicemail Drop, and (demoted) mic/speaker switching.
> Reviewed by: `buyer` agent (6-persona ICP panel). Verdict folded in below.
> Scope of this document: **product + UX + Apple-HIG design only.** No code. No error/edge-case handling.

---

## 0. Buyer verdict (locked decisions)

The request was for a **floating popup window**. The buyer panel (6 real cold-caller personas) **unanimously rejected a floating OS window** and locked the following instead. These are the load-bearing decisions for this plan:

| # | Decision | Rationale (from buyers) |
|---|---|---|
| 1 | **In-window center-view swap**, not a floating window | At 200 calls/day reps need the hang-up button in the *exact same pixel* every time (muscle memory). Floating windows drift behind the CRM / Google Sheet and cause mis-clicks. Matches the app's existing screen convention. |
| 2 | **Appears the instant Call is clicked**, in a **Calling… / Ringing** state | Half of all dials are misdials or bad-format numbers; the #1 emotional need is "kill it fast before it connects." A **Cancel** button sits in the exact pixel the **Hang up** button will occupy after connect. |
| 3 | **Primary controls = Mute · Keypad · Hold · Hang up · Voicemail Drop** | These are the only buttons used daily. Voicemail Drop was *missing* from the original ask and is used **more** than Hold. |
| 4 | **Mic + Speaker switching demoted** to a single "•••" / gear menu | Reps set their headset once per shift and never change it mid-call. It's a Settings concern, mirrored in-call only as a rare fallback. |
| 5 | **One** floating element allowed: a tiny **always-on-top Call HUD** (name + timer + Mute + Hang up) shown only when the main window loses focus | The single concession to the alt-tab-to-CRM reality, so a talking rep can always hang up. This is the closest thing to the originally-requested "popup," kept minimal. |
| 6 | **Notes field auto-focused** on the Ringing state; **disposition chips as one-key shortcuts** | Reps type notes while it rings and log outcomes with a keystroke, not a mouse. |

> **Open product decision for you:** you asked for a "pop up." The buyers strongly prefer the in-window screen (Decision 1) with the mini-HUD (Decision 5) as the only floating piece. This plan is written that way. If you want the *full* screen to be a real floating `Stage` instead, say so and I'll re-scope — but the panel's verdict was 6–0 against it.

---

## 1. What already exists (reuse, don't rebuild)

The codebase already has most of the shell. This plan **extends** it rather than starting fresh.

- **[ActiveCallController.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java)** — already has remote-party label, live duration timer, Mute toggle, Hold toggle, a (stubbed) Keypad button, Hang up, notes area, and a disposition `ComboBox`. Mute/Hold callbacks fire today but are **not wired to the telephony layer**. **Caveat:** `startCall(remoteDisplay, connectedAt)` requires a connect instant, immediately starts the timer, and calls `notesArea.clear()` — so it **cannot** represent a pre-answer Ringing state as-is. This plan splits it into `startRinging()` + `markConnected(Instant)` so notes typed while ringing are preserved (see gaps below).
- **[active-call-view.fxml](src/ui/src/main/resources/fxml/active-call-view.fxml)** — the current layout. Will be restyled to the Apple call-screen design below and gain the new states/controls (Voicemail Drop button, ••• header menu, keypad overlay node — all net-new).
- **[MainWindow.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/MainWindow.java)** — `showActiveCall(...)` / `showDialer()` already swap the center view via `BorderPane.setCenter(...)`; scene-level keyboard dispatch already routes keys to the active-call controller. **Caveat:** `showActiveCall` is currently invoked **only from `onCallAnswered`** (after 200 OK, at [ColdCallingApp](src/app/src/main/java/com/elitale/coldbirds/coldcalling/app/ColdCallingApp.java#L228), passing `Instant.now()`); opening on Call-click needs the new ringing trigger (see gaps).
- **[CallState.java](src/domain/src/main/java/com/elitale/coldbirds/coldcalling/domain/value/CallState.java)** — sealed `Idle / Ringing / Active / OnHold / Ended` models the lifecycle, including **OnHold**. **Caveat:** `CallState.Ringing` carries **inbound** fields (caller, arrivedAt); an outbound "calling" state is a semantic mismatch — either add an outbound variant or treat ringing as a UI-only state.
- **[AudioDeviceManager.java](src/telephony/src/main/java/com/elitale/coldbirds/coldcalling/telephony/audio/AudioDeviceManager.java)** — already enumerates and resolves input/output devices (used by Settings). The "•••" menu mirrors these lists.

### Capability gaps this feature depends on (telephony/services)
These are **functional gaps**, surfaced so the plan is honest about dependencies. Detailed telephony design is out of scope for this UX plan and would be its own plan.

| Capability | Today | Needed for |
|---|---|---|
| `CallService` / `TelephonyService` **hold / unhold** (SIP re-INVITE `sendonly`) | missing | Hold button |
| `CallService` / `TelephonyService` **mute / unmute** (pause RTP transmit) | missing | Mute button |
| `CallService` / `TelephonyService` **sendDtmf** (RFC 2833 or SIP INFO) | missing | Keypad |
| **Mid-call device switch** (rebuild audio pipeline live) | missing | "•••" mic/speaker menu |
| **Voicemail drop** (play a pre-recorded WAV into the call) | check power-dialer spec | Voicemail Drop button |
| **Icon library** (Ikonli + Bootstrap Icons pack, see §5.7) | not yet added | Every control, chip, menu item |
| **Pre-answer "ringing" trigger** — a dial-time hook (e.g. `CallService.setOnCallRinging`) wired into all three dial sites | missing (screen only opens from `onCallAnswered`) | Calling… / Ringing state on click |
| **Split controller contract** — `startRinging()` + `markConnected(Instant)` replacing the current `startCall` | needs change | Ringing → Active without wiping notes / starting the timer early |
| **Disposition label → `CallDisposition` enum mapping** | missing | Persisting the selected chip on hang-up |

> The Calling Screen UI can be built and demoed against the existing call lifecycle first; each control is wired as its telephony capability lands. Buttons whose capability isn't ready yet appear **disabled with a subtle "coming soon" affordance** rather than being hidden, so the layout never shifts.

---

## 2. Screen states (Apple "phone call" model)

The screen is a single view that moves through states. The control row's **footprint never changes** between states so targets stay put.

### 2.1 Calling… / Ringing (pre-connect)
- Large remote-party identity: **lead name** (or E.164 number if unknown), with the number as a secondary line, and the country flag + name.
- Status line: **"Calling…"** → **"Ringing…"** (animated subtle pulse on the avatar ring).
- **Notes field auto-focused** — the rep can start typing immediately. During Ringing the only active shortcut is **Esc → Cancel** (a scene accelerator that works regardless of focus); the bare one-key shortcuts come alive in the Active state, when notes is *not* auto-focused (see §6).
- Single prominent **Cancel** button (red, circular, bottom-center) — occupies the **exact pixel position** the Hang up button will take after connect.
- The other control buttons are present but **disabled/dimmed** (you can't mute a call that isn't connected) so nothing jumps when it connects.

### 2.2 Active (connected)
- Status line becomes a **live duration timer** (mono, green) — already implemented.
- Control row becomes fully enabled: **Mute · Keypad · Hold · Voicemail Drop · Hang up**.
- Disposition chips + notes remain visible below the controls.

### 2.3 On Hold
- Avatar ring + timer turn **amber**; status line reads **"On hold"**.
- The Hold button shows its **active/filled** state (label "Resume").
- Everything else stays in place.

### 2.4 Ended
- Brief **"Call ended · m:ss"** confirmation, then auto-return to the originating screen (dialer, side panel, or power-dialer queue — which auto-advances). Already wired via `onCallEnded`.

---

## 3. Controls — layout & behavior

### 3.1 Primary control row (the FaceTime-style circular row)
Left-to-right, fixed positions:

| Order | Control | Shape | Icon (idle → active) | Active state | Notes |
|---|---|---|---|---|---|
| 1 | **Mute** | circular, secondary fill | `bi-mic` → `bi-mic-mute-fill` | filled / red-tint, label "Unmute" | Pauses RTP transmit. |
| 2 | **Keypad** | circular, secondary fill | `bi-grid-3x3-gap` → `bi-grid-3x3-gap-fill` | opens DTMF overlay | See 3.2. |
| 3 | **Hold** | circular, secondary fill | `bi-pause-fill` → `bi-play-fill` | amber-filled, label "Resume" | SIP re-INVITE. |
| 4 | **Voicemail Drop** | circular, secondary fill | `bi-soundwave` | plays once, then disabled | Plays a pre-recorded greeting into a **connected** call — e.g. when the callee's voicemail system picks up. |
| 5 | **Hang up** | circular, **red/destructive**, slightly larger | `bi-telephone-x-fill` | — | Always the same pixel position as Cancel. |
| ↗ | **•••** (More) | circular, tertiary — **top-right of card, not in the row** | `bi-three-dots` | popover menu | Holds **Change microphone**, **Change speaker** (mirrors Settings). |

- The **•••** menu lives at the **top-right corner of the call card**, deliberately **not adjacent to Hang up** (avoids destructive mis-clicks; HIG).
- The **Cancel** button (ringing state) reuses the destructive Hang-up slot with `bi-telephone-x-fill`.
- Each circular button has its glyph (Ikonli `FontIcon`, see §5.7) plus a **text label underneath** (Apple call-screen convention) so the screen is learnable for new hires.
- Buttons are evenly spaced on the 8-pt grid; the row is horizontally centered.

### 3.2 Keypad (DTMF) overlay
- Tapping **Keypad** slides up an in-screen **3×4 dialpad** (1–9, ✶, 0, #) over the lower portion of the screen — it does **not** open a separate window.
- Pressing a digit sends the DTMF tone and shows the pressed digits in a thin readout strip (for IVR navigation like "press 1 for sales").
- A **Hide keypad** control collapses it back; the primary control row and Hang up remain reachable while the keypad is open.
- Opening the keypad moves key focus off the notes field; physical `0–9 * #` keys then send DTMF while the overlay is open.

### 3.3 "•••" More menu (demoted device switching)
- A small popover anchored to the ••• button, listing:
  - `bi-mic` **Microphone ▸** — the input device list from `AudioDeviceManager`, current one checked (`bi-check`).
  - `bi-volume-up` **Speaker ▸** — the output device list, current one checked (`bi-check`).
- Selecting a device applies it mid-call (depends on the mid-call-switch capability) and updates the checkmark. The source of truth stays **Settings**; this is a convenience mirror.

### 3.4 Disposition chips + notes
- The existing disposition set (today a `ComboBox` in `ActiveCallController`) is re-presented as **chips** with a leading icon — visual style borrowed from the recent-call detail panel; this is a **net-new control**, not a reuse of the ComboBox. Full set, matching the current values incl. **Failed**:
  - `bi-hand-thumbs-up` **Interested** · `bi-hand-thumbs-down` **Not Interested** · `bi-arrow-counterclockwise` **Callback** · `bi-soundwave` **Voicemail** · `bi-telephone-x` **No Answer** · `bi-dash-circle` **Busy** · `bi-slash-circle` **DNC** · `bi-exclamation-triangle` **Failed**.
- One-key shortcuts (see §6) select a disposition without the mouse — active only when notes is unfocused.
- Notes field shows a leading `bi-pencil` affordance; persists to the call record on hang-up (already wired through the controller callback; should be connected to `CallService` persistence, mapping the chip label to `CallDisposition`).

### 3.5 Always-on-top mini Call HUD (the one floating element)
- Appears **only when the main app window loses focus** during an active call.
- Contents: `bi-person-circle` avatar · lead name · `bi-clock` live timer · **Mute** (`bi-mic` / `bi-mic-mute-fill`) · **Hang up** (`bi-telephone-x-fill`). Nothing else.
- Small, draggable, frameless, rounded — sits above other apps so a rep on a call can always end it while in their CRM/Sheet.
- Disappears when the main window regains focus or the call ends.

---

## 4. Visual design — wireframes & layout

The screen is a single vertically-centered column on the app's `-cc-bg-primary` background. Max content width **~420px**, centered, so it reads as a focused "call card" regardless of window size. Vertical rhythm below; every gap is an 8-pt multiple.

### 4.0 Layout skeleton (component tree)

```
CallingScreen (VBox, centered, maxWidth 420, spacing 24, padding 32)
├── HeaderBar (HBox)           ← spacer + [•••] More menu, pinned top-right
├── AvatarBlock (StackPane)
│     ├── stateRing (Circle, 96px, 3px stroke, state-colored, pulse anim)
│     └── avatar (Circle 88px / initials Label or glyph)
├── IdentityBlock (VBox, spacing 4, centered)
│     ├── nameLabel            ← title-1 22/600
│     ├── numberRow (HBox)     ← flag(16px) + countryName + " · " + number(mono caption)
│     └── statusLabel          ← "Calling…" / "Ringing…" / timer / "On hold"
├── ControlRow (HBox, spacing 24, centered)        ← see §3.1
│     └── [Mute] [Keypad] [Hold] [Voicemail] [Hang up]
├── KeypadOverlay (collapsed by default)           ← see §3.2 / 4.5
├── DispositionChips (FlowPane, spacing 8)         ← chips, one-key
└── NotesField (TextArea, 3 rows, auto-focus on ring)
```

### 4.1 Ringing state (pre-connect)

```
┌──────────────────────────────────────────┐
│                                      •••   │   ← More menu (top-right, §3.3)
│                ╭────────╮                  │   ← stateRing pulsing, ACCENT #0071E3
│                │   AK    │                 │   ← 88px avatar (initials/glyph)
│                ╰────────╯                  │
│                                            │
│               Alex Kim                     │   ← title-1 22/600
│        🇺🇸 United States · +1 415 …         │   ← flag + country + mono number
│               Ringing…                     │   ← status, accent, subtle pulse
│                                            │
│      ◌      ◌      ◌      ◌     ( ✕ )       │   ← Mute Keypad Hold VM dimmed;
│    Mute  Keypad  Hold  Voicemail  Cancel   │     Cancel = red, 64px, bottom-center
│                                            │
│   ┌──────────────────────────────────┐    │
│   │ Notes… (cursor, auto-focused)     │    │   ← 3-row TextArea
│   └──────────────────────────────────┘    │
└──────────────────────────────────────────┘
```

- The four left controls are **present but dimmed/disabled** so nothing shifts on connect.
- **Cancel** sits in the *exact* x/y the **Hang up** button takes post-connect (locked pixel).

### 4.2 Active state (connected)

```
┌──────────────────────────────────────────┐
│                                      •••   │   ← More menu (top-right, §3.3)
│                ╭────────╮                  │   ← stateRing solid GREEN #34C759
│                │   AK    │                 │
│                ╰────────╯                  │
│               Alex Kim                     │
│        🇺🇸 United States · +1 415 …         │
│                 02:14                       │   ← live timer, mono 17, green
│                                            │
│     (🎙)   (⊞)   (⏸)   (✉)   ( ☎ )         │   ← all enabled
│    Mute  Keypad  Hold  Voicemail  Hang up  │
│                                            │
│  [Interested][Callback][Not int.][Busy]…   │   ← disposition chips (one-key)
│   ┌──────────────────────────────────┐    │
│   │ Notes…                            │    │
│   └──────────────────────────────────┘    │
└──────────────────────────────────────────┘
```

The **•••** button sits at the **top-right of the card** and opens the device menu (§3.3).

### 4.3 On Hold state

Identical layout; only color/label state changes:
- `stateRing` + timer recolor to **amber #FF9F0A**, status reads **"On hold"**.
- **Hold** button shows filled amber state, its label switches to **"Resume"**.

### 4.4 Ended state

```
┌──────────────────────────────────────────┐
│                ╭────────╮                  │
│                │   AK    │   (✓ fades in)  │
│                ╰────────╯                  │
│            Call ended · 02:41              │   ← brief confirmation
└──────────────────────────────────────────┘
        ↓ auto-returns after ~1s to origin
   (dialer / side panel / power-dialer next)
```

### 4.5 Keypad overlay (DTMF)

Slides up over the lower third; control row + Hang up stay reachable above it.

```
│        readout: 1 4 ★                       │   ← pressed-digit strip
│   ┌─────┐  ┌─────┐  ┌─────┐                 │
│   │  1  │  │  2  │  │  3  │                  │   keys: 64px, radius 12,
│   ├─────┤  ├─────┤  ├─────┤                  │   secondary fill, press-flash
│   │  4  │  │  5  │  │  6  │                  │
│   ├─────┤  ├─────┤  ├─────┤                  │
│   │  7  │  │  8  │  │  9  │                  │
│   ├─────┤  ├─────┤  ├─────┤                  │
│   │  ★  │  │  0  │  │  #  │                  │
│   └─────┘  └─────┘  └─────┘                  │
│              [ Hide keypad ]                 │
```

### 4.6 Mini Call HUD (focus-loss, the only floating element)

```
┌───────────────────────────────┐
│ Alex Kim        02:14   🎙  ☎ │   ← frameless, rounded 12, always-on-top
└───────────────────────────────┘     name · timer · Mute · Hang up only
```

~280×56px, draggable, drop shadow. Appears only when the main window blurs during an active call; dismisses on refocus or call end.

---

## 5. Apple design system spec (HIG)

Built on the existing **[cupertino-light.css](src/ui/src/main/resources/css/cupertino-light.css)** tokens. **Light mode only** (no dark mode in scope).

### 5.1 Layout & spacing
- **8-pt grid.** All paddings/gaps are multiples of 4.
- Screen vertical rhythm: avatar block (top) → identity → status/timer → control row → disposition chips → notes. Generous whitespace; content **centered** with comfortable side margins.
- Card/overlay corner radius: **8px** (cards), circular buttons fully rounded, keypad keys **12px**.

### 5.2 Avatar / identity
- Circular avatar placeholder (lead initials or a glyph), ~**88px**, with a 2px ring that animates a subtle pulse while ringing and recolors per state (accent → green active → amber hold).
- Identity uses **Inter** (bundled, SF-Pro analog): name at `title-1` (22/600), number at `caption` (12/400) mono, country flag image + name beside it (reuse `FlagImages`).

### 5.3 Control buttons
- Circular, **~56px** diameter, label beneath at `caption`.
- **Secondary** controls: soft neutral fill (`-cc-bg-secondary` #F5F5F7), icon in `-cc-text-primary`. **Active/engaged** toggles invert to a tinted fill.
- **Hang up / Cancel**: destructive **#FF3B30** fill, white glyph, slightly larger (~64px) to read as the primary action.
- **Hold active**: amber **#FF9F0A** tint. **Mute active**: red tint.
- Hover/press: gentle scale + brightness, Apple-style, no harsh borders.

### 5.4 Color tokens (state)
- Connecting/ringing accent: **#0071E3**.
- Active/connected: **#34C759** (timer + ring).
- On hold: **#FF9F0A**.
- Destructive: **#FF3B30**.
- Light mode only — reuse the existing light tokens; no dark variants.

### 5.5 Typography
- Titles `title-1` 22/600 · status `title-2` 17/600 · timer 17 mono · labels `caption` 12/400 · disposition chips `label` 13/500.

### 5.6 Motion
- State transitions cross-fade (~150–200ms). Avatar ring pulse while ringing. Keypad slides up/down. Subtle, never bouncy.

### 5.7 Iconography (icon library)

**Use icons everywhere** — every button, chip, menu item, status line, and the HUD carries a glyph. To do this consistently with an Apple feel, adopt a single vector icon library rather than hand-rolled SVG paths or flag-style PNGs.

**Library: [Ikonli](https://kordamp.org/ikonli/) + Bootstrap Icons pack.**
- **Why Ikonli:** the de-facto JavaFX icon framework. Icons render as native `FontIcon` nodes — vector, crisp at any size, styleable purely from CSS (`-fx-icon-color`, `-fx-icon-size`), and swappable per state (outline ↔ filled) without swapping image assets.
- **Why Bootstrap Icons:** the closest *open, redistributable* match to Apple **SF Symbols** — even stroke weight, rounded terminals, optically balanced, and ships both outline and `-fill` variants for engaged/active toggles. MIT-licensed, so it bundles cleanly into the jpackage build (SF Symbols itself is Apple-licensed and cannot be redistributed).
- **Dependencies (add to the `ui` module via `gradle/libs.versions.toml`):** `org.kordamp.ikonli:ikonli-javafx` and `org.kordamp.ikonli:ikonli-bootstrapicons-pack`. **Pin a current Ikonli (≥ 12.3.1)** so every referenced `bi-*` glyph resolves — older packs predate several of them. The `ui` module has no `module-info.java` (classpath build), so no JPMS `requires` is needed.

**Sizing** (inherits the 8-pt grid):
- Control-row glyphs **24px** inside 56px buttons; **Hang up / Cancel 28px** inside the 64px button.
- "•••" menu items **16px** leading; disposition chips **14px** leading; HUD **18px**; status-line prefix **15px**.
- Keypad keys stay **text** (digits/∗/#) with optional `bi-asterisk` / `bi-hash` for the symbol keys.

**Color:** glyphs inherit their container's foreground token; active toggles flip to the tinted `-fill` variant (Mute→red, Hold→amber). Disabled controls render at reduced opacity.

#### Full icon map

| UI element | State | Bootstrap Icons glyph |
|---|---|---|
| Mute | idle / muted | `bi-mic` / `bi-mic-mute-fill` |
| Keypad | idle / open | `bi-grid-3x3-gap` / `bi-grid-3x3-gap-fill` |
| Hold | hold / resume | `bi-pause-fill` / `bi-play-fill` |
| Voicemail Drop | idle / dropped | `bi-soundwave` (dimmed once used) |
| Hang up & Cancel | — | `bi-telephone-x-fill` |
| More menu | — | `bi-three-dots` |
| → Change microphone | — | `bi-mic` |
| → Change speaker | — | `bi-volume-up` |
| → selected device check | — | `bi-check` |
| Avatar fallback (no initials) | — | `bi-person-circle` |
| Identity — outbound marker | — | `bi-telephone-outbound` |
| Identity — country | — | reuse `FlagImages` PNG (unchanged) |
| Status — ringing | — | `bi-telephone-outbound` (pulsing) |
| Status — active timer | — | `bi-clock` |
| Status — on hold | — | `bi-pause-circle` |
| Status — ended | — | `bi-check-circle-fill` |
| Notes field | — | `bi-pencil` |
| Keypad — symbol keys | — | `bi-asterisk` / `bi-hash` |
| Keypad — hide | — | `bi-chevron-down` |
| Disposition — Interested | — | `bi-hand-thumbs-up` |
| Disposition — Not interested | — | `bi-hand-thumbs-down` |
| Disposition — Callback | — | `bi-arrow-counterclockwise` |
| Disposition — Voicemail | — | `bi-soundwave` |
| Disposition — No answer | — | `bi-telephone-x` |
| Disposition — Busy | — | `bi-dash-circle` |
| Disposition — DNC | — | `bi-slash-circle` |
| Disposition — Failed | — | `bi-exclamation-triangle` |
| HUD — avatar / timer | — | `bi-person-circle` / `bi-clock` |

> A handful of telephony-specific glyphs (a literal dial-pad, a voicemail tape) have no exact Bootstrap Icons match; the map above substitutes the nearest on-brand glyph (`bi-grid-3x3-gap`, `bi-soundwave`) to keep one consistent icon family. If an exact dial-pad/voicemail glyph is later required, pull only those two from the Ikonli Material pack — do not mix families wholesale.

---

## 6. Keyboard shortcuts

Consistent with the app's existing call shortcut (`Esc` = hang up) and the recent-call panel's letter chips.

**Focus model (resolves the auto-focus vs one-key tension):**
- **Esc** is a **scene-level accelerator** — it fires regardless of which control holds focus, in every state.
- During **Ringing**, notes is auto-focused and only Esc (Cancel) is active; the other controls are disabled anyway, so their bare shortcuts are intentionally inert.
- During **Active**, notes is **not** auto-focused; the bare one-key shortcuts below are live. Clicking into the notes field suppresses them until focus leaves (Esc or Tab).
- When the **keypad overlay is open**, it owns key focus: `0–9 * #` send DTMF; Esc / Hide closes it.

| Key | Action |
|---|---|
| `Esc` | Cancel (ringing) / Hang up (active) / close keypad — scene accelerator |
| `M` | Toggle Mute (active only) |
| `H` | Toggle Hold (active only) |
| `K` | Toggle Keypad (active only) |
| `0–9 * #` | Send DTMF (keypad overlay open) |
| `V` | Voicemail Drop (active only) |
| Disposition one-keys | Panel mnemonics (e.g. `I` Interested, `X` Not Interested, `B` Busy, `A` No Answer, `D` DNC, Callback, Failed) — active only, when notes is unfocused |

- Bare letter/number shortcuts only fire when **no text field holds focus**, mirroring the recent-call panel behavior.

---

## 7. Phasing

**Phase A — Icon library + Calling Screen shell + states (UI only).**
Add the Ikonli + Bootstrap Icons dependency (§5.7) to the `ui` module first. Add the **pre-answer ringing trigger** (`setOnCallRinging` wired into the three dial sites) and split the controller into `startRinging()` + `markConnected(Instant)`. Reframe the existing active-call view into the Apple call-screen design with the four states (Calling/Ringing → Active → On Hold → Ended), every control carrying its glyph. Wire it to open **on Call-click** in the Ringing state with auto-focused notes and a Cancel button in the locked pixel position. Disposition chips + one-key shortcuts. Buttons whose telephony capability isn't ready render disabled.

**Phase B — Wire the controls that already have a path.**
Hang up / Cancel (exists), Mute and Hold (UI exists; connect to the telephony hold/mute capabilities as they land), Keypad overlay + DTMF send, persist notes/disposition to `CallService` on hang-up (mapping the selected chip label to the `CallDisposition` enum before calling `updateDisposition`).

**Phase C — Polish + demoted/extra controls.**
Voicemail Drop button, the "•••" mic/speaker mirror menu, and the always-on-top mini Call HUD on focus-loss.

> Telephony enablement (hold re-INVITE, RTP mute, DTMF transport, mid-call device switch, voicemail playback) is tracked as a **separate dependency** — see the gap table in §1. The Calling Screen degrades gracefully (disabled control) until each lands.

---

## 8. Deliberately out of scope (YAGNI)

- **Floating full-screen Stage** — vetoed 6–0 by buyers; only the mini-HUD floats.
- **Mic/speaker as primary buttons** — demoted to the ••• menu; Settings remains the source of truth.
- **Mid-call transfer, conference, master volume slider** — not used by the ICP; revisit only if requested.
- **Per-call recording toggle** — leave under existing recording behavior for now.
- **Error / edge-case handling** (call failures, device-disappeared, re-INVITE rejection, network loss) — intentionally excluded from this plan per scope; handled when telephony capabilities are designed.
