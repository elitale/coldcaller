# Sidebar Redesign — navigation + ambient status rail

> Goal: turn the left navigation from a flat, text-only list into a polished rail an
> operator **trusts at a glance** — primarily by surfacing *ambient status* (am I
> registered? is a call live? did a lead reply?), not just prettier navigation.
>
> Scope: `ui/` only (one new view + small headless support classes) plus existing
> service seams. No telephony/storage schema changes for P0. **Plan only — do not implement.**

---

## 0. Buyer-agent validation (verdict that shaped this plan)

Stress-tested with the `buyer` agent (6 simulated cold-calling buyers/operators). Unanimous,
blunt takeaways:

- **The sidebar doesn't look "unfinished" because it lacks polish — it looks *untrustworthy*
  because it can't tell me if the phone works.** 6/6 ranked a **SIP-registration health
  indicator** as the single highest-value element — above icons, branding, everything.
- **Active-state + icons (A)** = table stakes. 6/6 need it; nobody pays for it; a missing
  active state has made operators click into the wrong screen mid-call.
- **Badges that cry wolf are fatal.** A count that's wrong or reappears after being cleared
  "destroys trust in *every* badge permanently." Ship a badge only when its state is truthful
  and synced — a missing badge is forgivable, a lying badge is not.
- **An optimistic green dot is worse than an honest amber one.** Need ≥3 states:
  `registered` / `reconnecting` / `offline`. Err toward showing trouble.
- **Missing from the original idea** (operators expect these): a **clickable "return to active
  call"** affordance from any screen; a **mic/audio-ready** signal; a **missed-callback**
  indicator distinct from unread SMS; and **"which number am I dialing FROM"** as
  error-prevention (not vanity branding).
- **Cut as v1 noise:** section grouping ("enterprise cosplay" at 6 items), permanent
  per-row shortcut hints (clutter → move to tooltips), and a standalone branding element
  (fold "BYO-Twilio connected" into the one registration dot).
- **Collapsible rail** = genuinely wanted on 13" laptops, but **P2**, and it must keep the
  status signals visible when collapsed.

Net: build **status + active-state + icons + a clickable live-call indicator** first; make
every badge truthful; skip the chrome.

---

## 0.1 Daily-operator validation (`va` agent — second lens)

Ran the same plan past the `va` agent (5 simulated SDRs/VAs/managers who live in the app
6–8 hrs/day). They confirmed the P0 trust direction but caught **daily-use papercuts the
buyer lens missed** and re-tiered two items:

- **Re-tier UP to P0 — "did a lead text back" is the highest-value event in a caller's day.**
  Ship a **truthful *presence dot*** on Messages now ("there is new activity") — that only lies
  if it shows with genuinely nothing, a far lower bar than a count. The exact **count** stays P1
  behind the seen-model. *"You're gating my most important signal behind your hardest
  engineering problem."*
- **Re-tier UP to P0 — the "Dialing from +1 415…" number.** For multi-number/multi-client
  callers, dialing a lead from the wrong client's number "gets me fired off an account." The
  number is a guardrail, not polish. (Mic-ready stays P1 / cut — see below.)
- **Motion is a liability on a panel watched out of the corner of one eye.** Two hard traps:
  - **No pulse on the ongoing live-call indicator** — a steady dot + climbing timer. Peripheral
    motion during a live call is an attention tax on every call. Reserve motion for
    *attention-needed* states only (incoming ring, reconnecting).
  - **Amber must NEVER appear on a healthy 60s re-REGISTER.** If amber flickers on normal
    refreshes, operators learn in a day that amber means nothing and stop seeing it — worse than
    no signal. The grace window is the whole ballgame.
- **Two week-one gaps the plan didn't cover:**
  - **Inbound call ringing *while* power-dialing** — the single highest-value interruption in
    cold calling — has nowhere to surface. The live row must handle *inbound ring*, not just my
    own outbound call, and carry **which number/client** it's for.
  - **OS-level notification when the window is unfocused/minimized** (Dock badge / native
    notification) for a text-back or callback. A sidebar dot you can't see does nothing; this is
    a separate surface but a real gap to track.
- **Keep the status dot strictly about SIP.** Never let green imply "your list is loaded /
  DNC-clean" — those are different trusts.
- **Icons are the most cuttable P0 item** (active-state comes free with the nav rework). Keep
  them only if they don't add a phase; nobody ever lost a call for lack of an icon.

**Floor's three-to-ship-this-quarter:** (1) honest SIP status dot, (2) clickable
return-to-call row **that includes inbound-ring**, steady not pulsing, (3) a truthful Messages
**activity dot** + the **dialing-from number**. Cut/defer: exact unread count, mic-ready green
state, collapsible rail.

---

## 1. Current state (what's wrong)

`MainWindow.buildSidebar()` builds a `VBox` inline:
- 190px, `bg-subtle` (#F5F5F7); app name "coldCalling" as a plain `title-2` label.
- A `Separator`, then six **text-only** `.flat` buttons (Dialer, Leads, Call History,
  Messages, Power Dialer), a spacer, another `Separator`, then Settings pinned at the bottom.
- Hover = faint gray fill. **No active state, no icons, no status, no badges.**

Gaps (operator-ranked): (1) no registration health, (2) no active-screen indicator,
(3) no live-call/power-dialer indicator + no way back to the call, (4) no icons, (5) no truthful
unread/callback signal, (6) no "dialing from" number.

---

## 2. Locked product decisions

- **Status is the headline, not branding.** A single honest connection indicator with three
  states lives near the app name; "BYO-Twilio connected" is *that* dot — not a separate element.
- **Three registration states, pessimistic bias:** `REGISTERED` (green), `RECONNECTING`
  (amber, gentle pulse gated by `Motion`), `OFFLINE` (red). Unknown/no-credentials → `OFFLINE`.
- **Active nav state is mandatory:** accent left-bar + tinted background + bolder label +
  tinted icon. Exactly one item active; driven by the centre view, not click-only.
- **Icons via existing Ikonli `bi-*`** (already used in `CallHudWindow`/`ActiveCallController`).
  No new dependency.
- **Live-call / inbound-ring / power-dialer indicator is clickable** — it returns to the
  active-call or power-dialer screen, and **also surfaces an inbound call ringing while
  dialing** (carrying which number/client it's for). Never decorative-only.
- **Motion means "look at me now," never "I'm still here."** The ongoing live-call row is
  **steady** (dot + climbing timer, no pulse). Motion is reserved for attention-needed states:
  incoming ring and `RECONNECTING`. Badge arrival is **quiet** (appear, don't bounce) so it
  never pulls focus mid-call.
- **Amber never fires on a healthy refresh.** A completed re-REGISTER inside the cycle must
  never have shown amber; amber = something is actually wrong. The grace window enforces this.
- **Truthful *presence* is P0; truthful *count* is P1.** A Messages **activity dot** ("new
  since you last looked") ships in P0 — it only lies if it shows with genuinely nothing. The
  exact unread **count** waits for a persisted seen-model (P1). No count ships until it can't
  lie or resurrect.
- **"Dialing from" number is P0** (multi-client wrong-number is the worst daily error);
  **mic-ready is P1 / cut** — show the device *name*, never a green "ready" we can't verify.
- **Status dot is strictly SIP health.** It never implies the list is loaded or DNC-clean.
- **NOT in v1:** section grouping, permanent shortcut hints (tooltips only, and only P2),
  standalone branding/logo-as-feature.
- **Collapsible rail is P2** and must preserve the status dot, live indicator, and badges as
  icon-level signals when collapsed.
- **Keep `MainWindow` ≤250 lines** for its own concerns → extract the rail into its own view +
  headless models (repo convention: headless support is unit-tested, JavaFX views are not).

---

## 3. Architecture & new components (bottom-up)

### 3.1 Existing seams to consume (no new service work for P0)
| Signal | Seam (already exists) |
|---|---|
| Registration (boolean) | `CallService.setOnRegistrationChanged(Consumer<Boolean>)` |
| Live call present | `MainWindow.updateHud()` path + `activeCallId` (app) + `CallHudVisibility` |
| Incoming call ringing | `CallService.IncomingCallListener` (fires regardless of which centre screen is showing) |
| Power-dialer running | `PowerDialerService.setOnSessionChanged(Consumer<Optional<PowerDialerSession>>)`, `getCurrentSession()` |
| Active outbound number | `CallerIdSelector` / `PhoneNumberService.getDefault()` |
| New SMS arrived (presence) | `SmsService.refreshInbound()` delta > 0 → set activity; clear when Messages opened (NOT a seen-count — see P1) |

### 3.2 Headless support (NEW, in `ui/support/` — **unit-tested**)
- **`RegistrationHealth`** — small state machine mapping the boolean
  `onRegistrationChanged` events (+ a grace window) onto the 3 states:
  - `true` → `REGISTERED`.
  - `true→false` after being registered → `RECONNECTING` for a grace window (the 60s
    re-REGISTER cycle); only escalate to `OFFLINE` after the window elapses with no success,
    or immediately when credentials are absent at startup.
  - Pure, time-injectable (pass a clock/`now` supplier) so transitions are testable without
    sleeps. **Carve-out:** keeps telephony untouched; a richer telephony `enum`+listener is the
    cleaner long-term seam but is deferred (YAGNI for v1).
- **`NavSelectionModel`** — the set of nav destinations + which is active; `select(dest)` and
  an `activeProperty`/listener so the view re-styles. Maps a centre-view identity → active item.
- **`SidebarStatusModel`** — aggregates `RegistrationHealth` + inbound-ring + live-call +
  power-dialer-running + Messages-activity into a tiny view-model the rail binds to (dot
  text/colour; which return-row to show and its label/elapsed, with precedence
  **inbound ring → live call → power dialer**; whether the Messages activity dot is lit). Pure,
  tested.
- **`NavBadge`** — P0 ships only a **presence** flag (`hasActivity`, set on new-SMS delta,
  cleared when Messages opens). P1 swaps in a truthful **count** fed by the persisted seen-model.
  Isolates the "don't lie" rule: presence is allowed in P0, a number is not.

### 3.3 View (NEW, `ui/controller/` or `ui/`)
- **`SidebarView`** (replaces `MainWindow.buildSidebar()`/`navButton()`): renders header
  (logo mark + name + status dot), the nav items (icon + label + active styling + optional
  badge), the **live-call/power-dialer indicator row** (clickable), a bottom **account chip**
  (P1), and Settings. Takes callbacks for navigation + "return to call"; binds to the headless
  models. Not unit-tested (JavaFX view).
- `MainWindow` shrinks to: construct `SidebarView`, wire service listeners → models via
  `Platform.runLater`, and provide the nav/return-to-call callbacks.

### 3.4 CSS (`cupertino-light.css`, additive)
New classes: `.nav-item`, `.nav-item:hover`, `.nav-item.active` (accent bar via left border
or a 3px `Region`, tinted bg `rgba(0,113,227,0.10)`, label 600, icon accent), `.nav-icon`,
`.status-dot` + `.status-dot.ok|.warn|.off`, `.nav-badge`, `.live-pill`, `.account-chip`,
`.nav-rail-collapsed` (P2). Reuse existing tokens (`#0071E3`, `#34C759`, `#FF9F0A`, `#FF3B30`).

---

## 4. UX / visual spec

### 4.1 Header + status (P0 — the headline)
```
┌──────────────────────────────┐
│  ◎ coldCalling               │   logo mark + wordmark
│  ● Connected                 │   status dot + honest label
└──────────────────────────────┘
```
- Dot + label: green "Connected" / amber "Reconnecting…" (gentle pulse, `Motion`-gated) /
  red "Offline". Tooltip carries the last detail (e.g. "403 — check Twilio credentials").
- This *is* the "BYO-Twilio connected" truth. One place, one truth.

### 4.2 Nav items (P0)
- Each item: `[icon] Label` with `-fx-graphic-text-gap` ~10px, left-aligned, 15px.
- Active: 3px accent left bar + `rgba(0,113,227,0.10)` bg + label weight 600 + accent icon.
- Inactive: label `#1D1D1F`, icon `#6E6E73`; hover `rgba(0,0,0,0.06)`.
- Proposed glyphs (final choice a detail): Dialer `bi-grid-3x3-gap-fill`, Leads
  `bi-people-fill`, Call History `bi-clock-history`, Messages `bi-chat-left-text-fill`,
  Power Dialer `bi-lightning-charge-fill`, Settings `bi-gear-fill`.

### 4.3 Live / inbound-ring / power-dialer indicator (P0 — the call-saver)
- A pill/row appearing **below the nav list** whenever there is something to return to:
  - **Inbound ringing** (highest priority) → `◉ Incoming · +1 512…` with the number/client;
    gentle attention pulse (this is an "act now" state); click → answer/active-call screen.
  - Ongoing live call → `● Live · 02:14` — **steady, no pulse**, timer climbs; click → returns
    to the active-call screen.
  - Power dialer running → `⚡ Dialing · 3/50`; click → returns to Power Dialer.
- Priority if several are true: **inbound ring → live call → power dialer** (most
  safety-critical first).
- Must remain a real, labeled-on-hover, clickable target even in the collapsed rail (P2).

### 4.4 Account chip — "dialing from" (P0 number; mic-ready P1)
- Bottom, above Settings: **`Dialing from +1 415…`** (active/sticky outbound number from
  `CallerIdSelector`) — **P0**, the guardrail against dialing a lead from the wrong client's
  number. For multi-client callers, pair the number with which client/list it maps to.
- **Mic-ready is P1 / cut:** show the selected input device *name*; only show a green "ready"
  if `AudioDeviceManager` can verify it. A confident-but-wrong "ready" is the worst failure in
  the plan — prefer the device name or nothing.

### 4.5 Truthful Messages signal (P0 dot / P1 count)
- **P0 — activity dot:** a small dot on Messages meaning "new inbound since you last opened it."
  Cheap, and only lies if it shows with genuinely nothing — a far lower bar than a count.
- **P1 — exact count** (`3`) on Messages + **missed-callback** count on Call History: gated on a
  persisted *seen* model (mark-read on open) so the number never lies or resurrects. Until then,
  ship the dot, not the number. (Buyer + operators: a lying badge is worse than none.)

### 4.6 Collapsible rail (P2)
- Toggle to an icon-only ~56px rail (persist via `SettingsService`). Collapsed **must** keep:
  status dot, live-call indicator (as an icon), and any badges. Labels move to tooltips.
  Shortcut hints (if any) live in these tooltips — never permanently on rows.

---

## 5. Threading
- All service callbacks (`onRegistrationChanged`, `onSessionChanged`, SMS poll) arrive
  off the FX thread → marshal to the models/view via `Platform.runLater`.
- The amber-pulse + live-elapsed timer use a single `AnimationTimer`/`Timeline`, gated by
  `Motion.isReduced()`; stop it when nothing is live to avoid idle repaint.

---

## 6. Phasing (each slice green via `./gradlew test`)

- **P0 — Trust + the call-savers (operator-confirmed top three + navigation):** ✅ **DONE**
  1. `RegistrationHealth` + `NavSelectionModel` + `SidebarStatusModel` (headless, tested — 20 tests).
  2. `SidebarView`: active state + status dot + the **live / inbound-ring / power-dialer return
     row** (steady live, pulsed ring) + Messages **activity dot** + **"dialing from" number**.
  3. Icons on nav items (Ikonli `bi-*`, verified resolvable in pack 12.3.1).
  4. Extract from `MainWindow`; wire `setOnRegistrationChanged` / live-call / incoming-call hooks
     → models. CSS classes added. `MainWindow` sidebar logic moved into `SidebarView`.

  **Implementation notes / deviations (carve-outs):**
  - **`NavBadge` folded into `SidebarStatusModel`** (one consumer in P0 — Messages presence).
    Keeping a separate badge class was premature; revisit when the 2nd badge (missed-callback)
    lands in P1.
  - **Messages activity dot is capability-complete but dark today** — the inbound-SMS receive
    pipeline (`smsService.startReceiving`) is currently disabled, so there is no honest feed to
    light it. `MainWindow.notifyInboundSms()` + `markMessagesSeen()` are wired; the dot lights
    when SMS-receive is re-enabled. (No lying badge — honest dark beats fake lit.)
  - **Single always-on 1s tick** drives the live timer, registration grace escalation, and the
    in-memory power-dialer snapshot. The plan's "stop when idle" was traded for one simple cheap
    timer (mutates nodes only on change). `dialing-from` is pushed (off-thread DB read), never
    polled on the tick.
  - **Inbound-ring** is set in `showIncomingCall` and auto-cleared by a `root.centerProperty`
    listener whenever the centre leaves the incoming overlay (answer / reject / nav).
- **P1 — Truthful counts + readiness:**
  5. Persisted seen-model → exact unread **count** (replaces the P0 dot) + missed-callback count.
  6. Mic/device readiness on the account chip (device *name* if state can't be verified).
  7. **OS-level notification** (Dock badge / native) for text-back / callback when unfocused.
- **P2 — Power-user polish:**
  8. Collapsible rail (persisted), status- and return-row-preserving when collapsed.
  9. Shortcut hints as tooltips.

---

## 7. Testing
- **Tested (headless):** `RegistrationHealthTest` (boolean+time → 3 states, incl. no-creds →
  OFFLINE, reconnect→recover, and **amber suppressed on a within-cycle re-register**),
  `NavSelectionModelTest` (single active invariant, view→item mapping),
  `SidebarStatusModelTest` (return-row precedence **inbound ring → live → power dialer**;
  activity dot lit on new delta, cleared on open; live label/elapsed),
  `NavBadgeTest` (P0 presence sets on delta + clears on open; P1 count never negative,
  cleared stays cleared until a new event).
- **Not tested:** `SidebarView` and CSS (JavaFX view, per repo convention).
- Coverage targets per `AGENTS.md` (services ≥90%, UI support is the tested surface here).

---

## 8. Deliberately deferred / NOT building (YAGNI — per buyer)
- **Section grouping / category headers** — "enterprise cosplay" at 6 items; revisit only past
  ~10 nav destinations.
- **Permanent per-row shortcut hints** — clutter on a panel stared at 7 hrs/day; tooltips only,
  and only at P2.
- **Standalone branding/identity element** — folded into the single registration dot; a small
  logo mark is a trivial visual, not a tracked feature.
- **Richer telephony registration enum** — P0 derives 3 states UI-side from the existing
  boolean seam; a first-class telephony state machine is a later cleanup.
- **Dark-mode pass** — tokens exist; ship light first, mirror classes when the app-wide dark
  theme lands.

---

## 9. Risks / open questions
- **Honest "reconnecting" window.** The boolean seam can't by itself distinguish "briefly
  re-registering" from "dead." The grace window in `RegistrationHealth` must be tuned so amber
  doesn't flicker on every 60s refresh nor hide a real outage. Validate against the actual
  re-REGISTER cadence; consider a future telephony signal for "attempting".
- **Truthful unread requires a seen-model** that doesn't exist yet — that's why badges are P1,
  not P0. Don't let badge work block the P0 trust win.
- **Mic-ready accuracy** depends on `AudioDeviceManager` state; a wrong "ready" is its own
  cry-wolf. If we can't read device readiness reliably, show device *name* only, not a
  green "ready".
- **Inbound ring while dialing (operator gap).** Surfacing an incoming call in the return row
  needs the incoming-call seam (`CallService.IncomingCallListener`) wired into
  `SidebarStatusModel`, plus the number→client/list association to label it. Confirm the
  incoming-call hook fires regardless of which centre screen is showing.
- **Notifications when unfocused (operator gap, P1).** A sidebar dot is invisible when the
  window is minimized/behind the CRM. Native notifications / Dock badge are a separate surface
  (likely `app/` + per-OS) — scoped out of the rail itself but tracked here as the real
  delivery mechanism for text-back/callback alerts.
