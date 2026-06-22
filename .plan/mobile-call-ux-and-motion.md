# Plan — Mobile-Style Call UX · Cold-Caller Functionality · Meaningful Motion

> Status: **PROPOSED — awaiting approval.** Product + UX + design only. No code in this doc.
> Trigger (verbatim): *"anything else is pending check and correct UX behaviour it should resemble how calls dialed and handled at mobile devices and must have functionality for the coldcallers check with buyer agent about this, try to add as much motion as possible, plan this."*
> Reviewed by: **`buyer` agent (6-persona ICP panel)** — Marcus (agency founder), Priya (solo freelancer), Jake (SDR manager), Carlos (founder-led sales), Lisa (VP Sales), Alex (the daily-veto SDR). Their verdict is folded in and **locks the decisions below**.
> Builds on shipped work: [active-call-audio-visualization.md](.plan/active-call-audio-visualization.md) (halo/ripple/waveform/device-switch — SHIPPED), [calling-screen.md](.plan/calling-screen.md) (the in-window call-screen model + the 6–0 veto of a floating call window), [dialer-ux-fixes.md](.plan/dialer-ux-fixes.md).

---

## 0. Buyer verdict (locked decisions)

The panel was blunt on two of the three asks:

1. **"Resemble a mobile phone" is the wrong *wholesale* north star.** A cold-calling rig is a *production line*, not a phone. Adopt only the mobile metaphors that **reduce friction at 150–250 dials/day**; reject the theatrical ones (slide-to-answer, heavy crossfades) that add latency and misfires.
2. **"As much motion as possible" is actively harmful.** Four of six buyers pushed back; two cited physical symptoms (nausea by hour 6; a rep with vestibular issues → an accessibility/WCAG 2.3.x liability). Motion at 6–8 hrs/day is fatigue, not delight. **Replace "maximum motion" with "maximum *meaningful* motion"** (the doctrine in §1).
3. **The list of 13 candidates misses the thing that actually loses agencies money:** **per-number reputation / "Scam Likely" health** + **local-presence area-code matching**. Escalated as its own plan (§4.6) — not built here, but it outranks every cosmetic item for the people paying.

### 0.1 MUST / NICE / SKIP (locked)

| # | Item | Verdict | Buyer reason (one line) |
|---|---|---|---|
| 8 | **Power-dialer disposition → auto-advance** | **MUST** | Kills the dead gap between calls — the #1 daily-time lever (Alex, Jake, Lisa). |
| 6 | **Voicemail drop** (un-stub) | **MUST** | 40–60% of dials hit VM; one-click drop saves 20+ min/day (Priya, Carlos, Alex). |
| 5 | **Recording indicator** | **MUST** | TCPA/consent visibility; non-negotiable for B-segment rollout (Jake, Lisa, Marcus). |
| 7 | **Mini Call HUD** (alt-tab pill) | **MUST** | Reps live in CRM/Sheet; the call must not "disappear" (Marcus, Carlos, Jake). |
| 1 | **Dial-pad press motion** | **NICE** | Yes — but instant ≤80ms feedback, not a show (Alex, Carlos). |
| 9 | **Queue preview (next N)** | **NICE** | Helps pacing/prep; not a blocker (Jake). |
| 12 | **Big speaker/headset toggle** | **NICE** | Promote from the ••• menu; mid-shift device swaps are common (Alex). |
| 10 | Voicemail detection / AMD | **NICE (risky)** | Big lift *if accurate*; false positives clip live humans and tank connect rate (Lisa). Prove first. |
| 11 | Call waiting / 2nd inbound | **NICE** | Real for inbound-heavy teams; edge case for solos (Jake yes; Priya/Carlos meh). |
| 3 | Avatar on incoming | **NICE (initials only)** | Initials fine; a contact-photo pipeline is wasted on cold prospects (Lisa, Alex indifferent). |
| 4 | Smooth screen transitions | **SKIP-ish** | Keep the existing 220ms active-view fade; **never** extend a transition in front of "dial/talk" (Alex, Carlos). |
| 2 | Slide-to-answer / decline | **SKIP** | Slower, misfire-prone; solves a touchscreen problem you don't have (4 of 6 reject). |
| 13 | "Motion everywhere" | **SKIP** | Fatigue + accessibility liability; replaced by the §1 doctrine (all six). |

### 0.2 Top 3 to build next (buyer-ordered)
1. **Power-dialer disposition → auto-advance (#8)** — highest daily-time ROI; the difference between a "dialer" and a "power dialer."
2. **Voicemail drop (#6)** — most-requested concrete capability; ship it *with* #8 so VM → drop → advance is one flow.
3. **Recording indicator + Mini Call HUD (#5 + #7) together** — #5 unblocks compliance-gated buyers; #7 is the one legitimate mobile metaphor (the PiP call pill) and is the natural home for the recording dot while alt-tabbed.

---

## 1. The Motion Doctrine (locked design rule)

> **Motion must encode a state change the rep needs to perceive.** It completes in **≤150ms** (≤250ms only for a *once-per-call* transition like connect / wrap-up), **never loops** unless it represents a **live ongoing state** (recording, remote-voice, mic-live), and **never blocks input**. A **"Reduce Motion"** setting (honoring OS `prefers-reduced-motion`) kills all non-essential animation. **If a motion can't name the signal it carries, cut it.**

This is how we satisfy "add as much motion as possible" responsibly — we **maximize the motion that carries signal** and **delete the motion that only carries fatigue**.

| Motion (signal-bearing — KEEP/ADD) | Carries the signal | Motion (decorative — BANNED) |
|---|---|---|
| Audio-reactive avatar halo + ripple (shipped) | "they're talking" | Bouncy/elastic easing on anything pressed 200×/day |
| Live mic waveform (shipped) | "your mic is live / muted" | Ambient background / parallax animation |
| Pulsing recording dot (§4.2) | "you are being recorded" | Gratuitous crossfades between screens |
| Dial-pad press flash ≤80ms (§4.3) | "the digit registered" | Any motion >150ms between rep and next action |
| Connect bloom + ring blue→green (§4.5) | "connected — start talking" | Looping/pulsing that isn't a live state |
| Power-dialer advance slide ≤200ms (§4.1) | "next contact loaded" | Decorative spinners where a state pill would do |

**Accessibility:** add a `KEY_UI_REDUCE_MOTION` setting (Settings → Appearance) + read OS hint. When on: halo/ripple/waveform freeze to a static state, recording dot becomes a static dot, transitions become instant. This is a one-time gate consulted by the existing `AnimationTimer`/`Timeline` start points in [ActiveCallController.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java).

---

## 2. Pending-work audit (what's actually incomplete today)

Grounded in the current code. "Seam" = where the work attaches.

| Area | State today | Seam | Verdict |
|---|---|---|---|
| **Voicemail drop** | Button **disabled**, tooltip "coming soon" ([ActiveCallController.java#L196](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java#L196)). Setting flag `isVoicemailDropEnabled()` exists ([SettingsService.java#L153](src/services/src/main/java/com/elitale/coldbirds/coldcalling/services/SettingsService.java#L153)). **No greeting-WAV setting, no telephony WAV-playback capability.** | telephony WAV→RTP inject + greeting file setting + un-stub button | **MUST — Phase 2** |
| **Power-dialer inter-call gap** | Answered calls **wait for a manual `advance()`** ([PowerDialerService.java](src/services/src/main/java/com/elitale/coldbirds/coldcalling/services/PowerDialerService.java) `advance()` / `notifyCallEnded`); no disposition-driven advance | chip/one-key → `CallService.updateDisposition` + `PowerDialerService.advance()` | **MUST — Phase 1** |
| **Recording indicator** | Recording produces a path (`telephony.takeRecordingPath`) but **no on-screen cue**; no recording-state exposed to UI | surface recording-active from telephony → CallService → controller | **MUST — Phase 1** |
| **Mini Call HUD** | Not built. The one floating element allowed by the 6–0 veto ([calling-screen.md §0.5](.plan/calling-screen.md)) | a small always-on-top `Stage` + window focus listener | **MUST — Phase 3** |
| **Dial-pad press feedback** | Pad buttons are flat, no press motion ([dialer-view.fxml#L33-L64](src/ui/src/main/resources/fxml/dialer-view.fxml#L33)) | CSS `:pressed` + ≤80ms scale on `onDigitPressed` ([DialerController.java#L324](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/DialerController.java#L324)) | **NICE — Phase 1** |
| **Speaker/headset toggle** | Demoted to the ••• menu (shipped); no primary control | promote a primary "Audio output" control on the call card | **NICE — Phase 3** |
| **Queue preview** | Power dialer shows only the *current* contact; next-N invisible | new `PowerDialerService.upcoming(int n)` query + a list panel | **NICE — Phase 1/2** |
| **Number field = Label + fake caret** | Flagged in [dialer-ux-fixes.md](.plan/dialer-ux-fixes.md) Phase 1 (real `TextField`) | pre-existing plan | **Track separately (dialer-ux-fixes)** |
| **Country selector bugs** (Up/Down, Enter, stale text) | Flagged in [dialer-ux-fixes.md](.plan/dialer-ux-fixes.md) Phase 2 | pre-existing plan | **Track separately (dialer-ux-fixes)** |
| **Settings constants vs keys** | `PowerDialerService` hardcodes `NO_ANSWER_MS`/`AUTO_ADVANCE_MS` though `KEY_DIALER_NO_ANSWER_TIMEOUT` / `KEY_DIALER_AUTO_ADVANCE_DELAY` exist | read settings in the service | **Tidy-up — fold into Phase 1** |
| AMD / call-waiting / transfer / conference / video | Not built | — | **SKIP / prove-first (§4.7, §8)** |

---

## 3. Mobile-likeness: adopt / adapt / reject

| Mobile metaphor | Decision | Why |
|---|---|---|
| Dial-pad **press feedback** | **Adopt** (instant ≤80ms) | Phones do this because touch has no key travel; reps mash the pad and need "did it register." |
| **Recording indicator** | **Adopt** | The single most legitimately mobile-borrowed item — legal/consent state. |
| **PiP call pill** when away | **Adopt as the Mini HUD** | Reps live in another window; the call must remain killable/visible. |
| **Big speaker/output toggle** | **Adopt (promote)** | Phones surface device routing as a primary control; ours is buried in •••. |
| **Connect transition** (ring→active) | **Adapt (minimal, ≤250ms, once)** | Useful as a "start talking" cue; must never delay talk. |
| **Initials avatar** on incoming | **Adapt (initials only)** | Fine as a tiny touch; a *photo pipeline* is wasted on cold prospects. |
| **Slide-to-answer / decline** | **Reject** | Slower than Space/Esc, mouse-misfire-prone on hot leads; solves a touchscreen-only problem. |
| **Heavy crossfade/slide** between every screen | **Reject** | Any transition in front of dial/talk is a regression at volume. |
| Contact photos, video/FaceTime, master volume slider | **Reject (YAGNI)** | Not used by the ICP. |

---

## 4. Features by phase

Each feature lists **behavior · motion (per §1) · layers · seams · TDD**. Buttons whose telephony capability isn't ready render **disabled with a subtle affordance** (never hidden) so the layout never shifts — consistent with [calling-screen.md](.plan/calling-screen.md).

### 4.1 Power-dialer disposition → auto-advance (MUST #8) — Phase 1

- **Behavior:** During an **answered** power-dialer call, the rep picks a disposition (chip click or one-key `1–8`, already wired in [ActiveCallController](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java)). When a session is active, that selection **persists the disposition AND advances to the next contact in one action** — *non-blocking*: the note/disposition save runs async ([CallService.updateDisposition] by Call-ID) while `PowerDialerService.advance()` loads the next contact immediately. Closes the dead gap Alex loses ~1 hr/day to.
- A small **"Disposition required to advance"** guard is optional (Jake wants tempo, not a nag) → **default off**; advancing without a chip logs the existing reason-based status (today's behavior).
- **Mobile-likeness:** none — this is the cold-caller-specific superpower mobile phones don't have.
- **Motion:** the current-contact card **cross-slides** (old card slides up + fades, new slides in from below) in **≤200ms**, once per advance; the Dialed/Connected/Remaining tiles **count-tick** to their new value. Cosmetic only — the dial fires immediately underneath.
- **Layers:** `services` (orchestration seam already exists), `ui` (PowerDialer + ActiveCall controllers), `app` (wire the disposition→advance composition, mirroring how `notifyCallAnswered/Ended` are already composed).
- **Seams:** [PowerDialerService.advance()](src/services/src/main/java/com/elitale/coldbirds/coldcalling/services/PowerDialerService.java); `CallService.updateDisposition`; the app already composes power-dialer notifications.
- **Also fold in (tidy-up):** make `PowerDialerService` read `KEY_DIALER_NO_ANSWER_TIMEOUT` / `KEY_DIALER_AUTO_ADVANCE_DELAY` instead of the hardcoded constants.
- **TDD:** `PowerDialerServiceTest` — disposition-advance increments position, marks the entry, loads next, exhausts cleanly; reads timeouts from settings; advance is a no-op when no session / not Running.

### 4.2 Recording indicator (MUST #5) — Phase 1

- **Behavior:** While a call is **ACTIVE/HOLD and being recorded**, show a **red ● + "REC"** chip near the status line (and in the Mini HUD, §4.4). Reflects real recording state — not a toggle (recording behavior is unchanged; the dot only *surfaces* it). If a future recording on/off setting lands, the dot gates on it.
- **Mobile-likeness:** direct adopt (phones show recording/consent state).
- **Motion:** opacity **1.0 ↔ 0.35 sine over ~1.2s, looping** — explicitly allowed by §1 because it represents a **live ongoing state**. Freezes to a static dot under Reduce Motion.
- **Layers:** `telephony` (expose recording-active, e.g. `boolean isRecording(String callId)` reflecting `activeRecorder`), `services` (`CallService.isRecording()` passthrough), `ui` (controller binds a pulsing node).
- **Seams:** TelephonyService `activeRecorder` / `takeRecordingPath`; controller status row in [active-call-view.fxml](src/ui/src/main/resources/fxml/active-call-view.fxml).
- **TDD:** `TelephonyService`/`CallService` recording-state getters (true mid-record, false after `takeRecordingPath`); controller behavior verified by visual check (UI convention).

### 4.3 Dial-pad press motion (NICE #1) — Phase 1

- **Behavior:** Pressing a dial-pad key (mouse or physical `0–9 * #`) gives **instant tactile feedback** on both the **dialer pad** and the **in-call DTMF keypad**.
- **Mobile-likeness:** adopt — the *one* dial-pad mobile-ism worth having.
- **Motion:** scale **1.0 → 0.94 → 1.0 + brief fill flash, ≤80ms**. **No ripple, no bounce** (the buyers explicitly rejected a 200ms ripple repeated 200×/day).
- **Layers:** `ui` (CSS `:pressed`/`:armed` on `.dial-key` + a tiny `ScaleTransition` on `onDigitPressed`).
- **Seams:** [DialerController.onDigitPressed](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/DialerController.java#L324); [dialer-view.fxml](src/ui/src/main/resources/fxml/dialer-view.fxml#L33); in-call keypad in [ActiveCallController](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java).
- **TDD:** none meaningful (render-only); visual check, consistent with the module's convention.

### 4.4 Mini Call HUD (MUST #7) — Phase 3

- **Behavior:** A small **always-on-top, frameless, rounded** window appears **only when the main window loses focus during an active call**. Contents: contact name · live timer · **recording dot** · **Mute** · **Hang up** — nothing else. Draggable. Dismisses on refocus or call end. This is the **only** floating element (per the 6–0 veto).
- **Mobile-likeness:** adopt — the PiP call pill, the most-justified mobile metaphor for this ICP.
- **Motion:** fade+scale-in **150ms** on appear; fade-out on dismiss. The recording dot reuses §4.2's pulse.
- **Layers:** `ui` (new `CallHudWindow` — a secondary `Stage`, `StageStyle.TRANSPARENT`, `setAlwaysOnTop(true)`), `app`/`MainWindow` (focus listener on the primary `Stage`, show/hide while a call is active; reuse the existing mute/hangup callbacks).
- **Seams:** [MainWindow](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/MainWindow.java) owns the stage + call callbacks; bind HUD timer to the same duration source as the call card.
- **TDD:** timer-format / visibility-state logic unit-tested where pure; window behavior visual.

### 4.5 Connect transition motion (Adapt #4, minimal) — Phase 1 (rides on §4.2)

- **Behavior:** The ringing→active moment gets **one** crisp cue: state ring **blue→green** + a **single halo bloom** (reuse the shipped halo) + the existing connect chime.
- **Motion:** **≤250ms, once per call**, never blocks. No other screen-to-screen transitions are added; dialer↔call stays the existing instant swap + 220ms view fade.
- **Layers:** `ui` only ([ActiveCallController.markConnected](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java)).
- **TDD:** visual.

### 4.6 Big speaker/headset toggle (NICE #12) — Phase 3

- **Behavior:** Promote audio-output selection to a **primary control** on the call card (cycles/loops the output device, or opens the device list), keeping the ••• menu as the full picker. Surfaces *which* output is active (the gap noted in the audit).
- **Mobile-likeness:** adopt — phones make routing a primary control.
- **Motion:** none beyond the standard ≤80ms press.
- **Layers:** `ui` (reuse the shipped `switchAudioDevices` path); no telephony change.
- **TDD:** existing device-switch coverage; control wiring visual.

### 4.7 Voicemail drop (MUST #6) — Phase 2 (the telephony lift)

The biggest dependency. Sequenced so the UI degrades gracefully until each piece lands.

- **Behavior:** On a **connected** call that hit the prospect's voicemail, **one click / `V`** plays a **pre-recorded greeting WAV** into the call, then (in a power-dialer session) **auto-advances** — VM → drop → advance as a single flow (compounds with §4.1).
- **New capability (telephony):** inject a **G.711 8 kHz mono WAV** into the **RTP send** stream for the file's duration (swap the mic source for file playback, then restore/****hang per setting). Must not disturb the recorder or RTP session.
- **New setting:** a **greeting-WAV path** + a **record/upload** affordance in Settings (record via the existing audio capture, or pick a file; store under `~/.coldcalling/`). Gate on the existing `isVoicemailDropEnabled()`.
- **Motion:** the Voicemail button shows a **determinate progress ring** for the drop duration, then settles to "dropped/dimmed." Live-state, allowed.
- **Layers:** `telephony` (WAV→RTP injector + format validation), `services` (`CallService.dropVoicemail()`), `app`/`ui` (un-stub the button, one-key `V`, Settings greeting UI), `storage`/`settings` (greeting path key).
- **Seams:** un-stub [ActiveCallController#L196](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/ActiveCallController.java#L196); `SettingsService` (add `KEY_DIALER_VOICEMAIL_GREETING_PATH`); the RTP/audio pipeline (`AudioPipeline`) is the injection point.
- **TDD:** `telephony` — WAV decode → PCMU frames, duration math, format-mismatch rejection, recorder/RTP untouched during playback; `services` — `dropVoicemail` no-ops with no active call / disabled setting / missing greeting; power-dialer VM→drop→advance composition.

### 4.8 Queue preview (NICE #9) — Phase 1/2

- **Behavior:** In the power dialer, show the **next N contacts** (name + company) below the current-contact card so reps can mentally prep (Jake's pacing ask).
- **Motion:** the imminent contact subtly highlights; on advance the list scrolls up one (rides §4.1's ≤200ms slide).
- **Layers:** `services` (new `PowerDialerService.upcoming(int n)` read), `ui` (a compact list in [power-dialer-view.fxml](src/ui/src/main/resources/fxml/power-dialer-view.fxml)).
- **TDD:** `PowerDialerServiceTest` — `upcoming(n)` returns the next n entries, clamps at end, empty when exhausted/idle.

### 4.9 The escalated gap (Section D) — **separate plan, not built here**

> **Per-number reputation / "Scam Likely" health + local-presence area-code matching.** The buyers' loudest miss: agencies lose clients when a number silently goes "Spam Likely" and torches connect rates. The schema already carries `reputation` (clean/warning/flagged) on `phone_numbers`, so a seam exists — but live surfacing, daily-volume throttle warnings, auto-rotation away from degraded numbers, and area-code-matched number selection are a **substantial standalone feature**. **Recommend a dedicated `.plan/number-reputation-health.md`.** Flagged here so it isn't lost; explicitly **out of scope** for this call-UX/motion plan.

---

## 5. Module-boundary check (AGENTS.md)

- `domain` — untouched (reuses existing `CallDisposition`, `PowerDialerState`, `reputation`).
- `telephony` — recording-state getter (Phase 1); WAV→RTP injector (Phase 2). No UI/storage deps. ✅
- `services` — `CallService.isRecording`/`dropVoicemail` passthroughs; `PowerDialerService` disposition-advance + `upcoming(n)` + settings-driven timeouts. ✅
- `ui` — recording dot, dial-pad/keypad press motion, advance slide, connect bloom, Mini HUD, speaker toggle, queue preview, Reduce-Motion gate. `ui→telephony` edge already allowed. ✅
- `app` — compose disposition→advance and HUD focus show/hide (mirrors existing call-event composition). ✅
- No FX-thread audio I/O; no audio-thread FX calls (pull-model via the shipped `AnimationTimer`). ✅

---

## 6. TDD test matrix

| Test class | Cases |
|---|---|
| `PowerDialerServiceTest` (extend) | disposition-advance loads next / marks entry / exhausts; `upcoming(n)` bounds; timeouts read from settings; advance no-ops off-session |
| `CallServiceTest` (extend) | `isRecording` true mid-record / false after take; `dropVoicemail` no-ops (no call / disabled / missing greeting); disposition+advance composition |
| `TelephonyServiceTest` / pipeline tests (extend) | recording-active getter; WAV→PCMU frame conversion + duration; format-mismatch rejection; RTP + recorder untouched during VM playback |
| `SettingsServiceTest` (extend) | greeting-path get/set default; reduce-motion get/set default |
| UI controllers | visual verification (module has no controller-test harness — consistent convention) |

Coverage targets unchanged: services ≥ 90%, telephony ≥ 80%, domain ≥ 95%. Hardware-touching paths guarded with `Assumptions`.

---

## 7. Phasing (ordered, buyer-prioritized)

**Phase 1 — Daily-time wins + signal motion (UI/services, no new telephony).**
Power-dialer disposition→auto-advance (#8) · recording indicator (#5) · dial-pad/keypad press motion (#1) · connect bloom (#4 minimal) · queue preview (#9) · Reduce-Motion setting · settings-driven dialer timeouts. *Ships fast, moves Lisa's metric, satisfies the "motion" ask where it carries signal.*

**Phase 2 — Voicemail drop (#6), the telephony lift.**
WAV→RTP injector · greeting-WAV setting + record/upload UI · un-stub button + `V` · power-dialer VM→drop→advance single flow.

**Phase 3 — The floating pill + device routing.**
Mini Call HUD (#7, recording dot lives here too) · promote speaker/headset toggle (#12).

**Prove-first / later:** AMD (#10) only after real-carrier detection-accuracy proof (a false positive that clips a live prospect is worse than no AMD) · call waiting (#11) for inbound-heavy teams.

**Separate plan:** number-reputation health + local presence (§4.6).

---

## 8. Deliberately NOT doing (YAGNI / buyer-vetoed)

- **Slide-to-answer / slide-to-decline** — vetoed 4–6; slower and misfire-prone vs Space/Esc.
- **"Motion everywhere" / ambient animation** — replaced by the §1 signal-only doctrine; fatigue + accessibility liability.
- **Heavy crossfade/slide between every screen** — keep the existing instant swap + 220ms active-view fade; never gate dial/talk behind animation.
- **Contact-photo pipeline** — initials only; photos are wasted on cold prospects.
- **3-way / conference merge, blind/attended transfer, video/FaceTime, master-volume slider** — not used by the ICP.
- **Recording on/off per-call toggle** — leave recording behavior as-is; only *surface* it (§4.2).
- **AMD shipped blind** — gate behind accuracy proof.
- **Number-reputation feature inside this plan** — escalated to its own plan (§4.6).

---

## 9. Confirm before execute (recommended defaults inline)

1. **Scope of first cut** — recommend shipping **Phase 1 only** first (fast, high-ROI, no telephony risk), then Phase 2/3. *(Default: yes.)*
2. **Disposition-to-advance guard** — advance **without** requiring a chip (logs today's reason-based status), i.e. **no nag**? *(Default: no guard — Jake wants tempo.)*
3. **Recording dot** — assume **recording is always-on while connected** and the dot reflects that, with no new toggle? *(Default: yes.)*
4. **Reduce-Motion** — add the setting **and** honor OS `prefers-reduced-motion`? *(Default: yes.)*
5. **Voicemail greeting** — support **both record-in-app and file-pick**, stored under `~/.coldcalling/`? *(Default: yes.)*
6. **Number-reputation health** — spin up a **separate plan** next, given the panel ranked it above every cosmetic item? *(Default: yes — recommend it.)*
