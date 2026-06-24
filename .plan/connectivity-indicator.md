# Connectivity — one honest "can I call right now?" signal (not a jail)

> One line, taped to the monitor (the buyer floor's words): **the internet being down doesn't disable
> my keyboard, my notes, or my need to hit "end call" on a dead call — tell me I'm offline, don't cage
> me for my ISP's mistake.**
>
> Scope: `ui/` + a small network probe (`telephony/` or `app/`) + `services/` (power-dialer pause).
> **Plan only — do not implement.**

---

## 0. Buyer-agent validation (the verdict that reshaped this feature)

Stress-tested with the `buyer` agent (6 cold-calling buyers/operators). Two clean results:

- **The indicator: unanimous yes (6/6 need it).** "Half my team's 'the tool is down!' tickets are
  actually *their* wifi — a clear offline state ends that argument" (Marcus). Priya (cheap hotspot,
  drops 10×/day): "knowing instantly = I stop making excuses to clients mid-call."
- **The hard full-window block: 0/6 — a trap.** "Offline kills *network* actions, not *local* ones.
  Block the network buttons (Dial, Send SMS), not the window." Alex (the daily-driver veto): "covering
  my whole screen when wifi drops for 3 seconds — I was typing a note, where did it go? Now I'm
  raging." A full-screen takeover **never reads as reassuring — it reads as 'the app crashed,'** the
  opposite of the trust you're building (Priya would trust it *less* than Google Voice).

> "Build the honest 'can I call?' signal and call-state-aware graceful degradation. **Kill the
> input-blocking overlay — it demos as 'protective' and ships as 'the app froze and ate my note at
> hour 6.'**"

The only buyer who liked a stop-state (Lisa, VP) flipped to **against** the moment it could touch a
logged call outcome. **No buyer supports a destructive hard block.**

## 0.1 The original ask vs. the verdict (what we're actually building)

The request was: detect wifi up/down, show an online/offline indicator (+ animation, proper icon), and
**"hover all over the window so the user cannot touch anything"** when offline. We keep the honest
intent — *make "you can't call right now" unmistakable* — and **drop the input-blocking scrim.** The
one place a prominent takeover is allowed (per the buyer) is a **sustained (>60s) outage on an idle
screen**, and even there it's a **centered card with the rest of the app dimmed-but-still-usable**, not
a keyboard-stealing modal. Never mid-call, never mid-wrap-up.

---

## 0.2 VA-agent validation (daily operator — second lens)

Run past the `va` agent (Alex: ~190 dials/day, home wifi browns out 4-5×/shift, café captive-portal
twice a week — the worst case). Verdict: "**Build it — the bones are right, and killing the scrim saved
you a refund ticket from me.** But it's tuned for *reassurance* (a calm dot that changes colour) when
what I need is *interruption at the one moment that matters: the half-second I'm about to hit Dial on a
dead line.* A dot in the sidebar is in my blind spot at hour 6 — I'm staring at the lead card and my
notes, not the corner." Five changes before we lock it:

1. **The Dial / Send button IS the interrupt surface — not the sidebar dot.** On offline the button's
   *own label* becomes **"Offline"**, and a **click-time pre-flight reachability check** means a dial
   fired during the grace window **fails instantly with a clear reason** instead of a 10s dead INVITE
   timeout. "Move the signal from the corner I'm not looking at to the button I'm about to press."
2. **Disable Dial at ~3s (with the amber), not 5s.** Bias *jumpy* on the disable — a lost dial on a 2s
   blip is cheap (click again); a dial fired into a dead line eats a 10s timeout and kills trust in the
   button. Keep the loud red **"Offline"** label slow (8-10s) so it doesn't flap.
3. **"Call lost" must be actionable, and a mid-call drop must NOT auto-advance the dialer.** The note
   carries the **lead name + number + one-tap redial** (now / when back online); the drop routes into
   **pause-and-hold** (a drop is not a completed call — never skip a live prospect).
4. **Resume prompt: name it, make it distinct, add an end-session escape.** "Resume with **Maria Lopez
   (47 of 120)?**" — a number alone means nothing; a **distinct inline panel** (not a reflex-dismissed
   corner toast); after a **long outage (>2-3 min)** offer **"resume from 47, or end session?"**
5. **Probe = a real TCP connect to the SIP/Twilio host, 2-3s timeout** — *not* an HTTP-200 from
   anywhere, *not* google. **Captive-portal café wifi** passes a NetworkInterface check and answers a
   google ping but fails the SIP handshake; only a real TCP connect catches it. And a **single failed
   probe must never disable anything** — only sustained failure through the grace window — or slow-but-
   working 1.5s café wifi gets murdered (false-offline).

Operator notes: the **"Back online" toast is duration-gated** — a 4s blip just flips the dot green (no
toast); the toast only fires after a *long* (>20-30s) outage where trust was actually lost. A dropped
**inbound** ring is logged **"missed — connection lost"** so a callback never silently vanishes; a
disabled **Send** with text typed reads **"will send when back online"** (the SMS queue itself stays
P2). And the **>60s dimmed idle card is vanity — drop it** ("the scrim wearing a calmer hat"). Optional:
a single soft sound on the *transition* to offline (a setting), since the dot is in the blind spot.

---

## 1. Current state

- **`RegistrationHealth`** (ui/support, tested) is a 3-state **SIP-registration** machine
  (`REGISTERED` / `RECONNECTING` / `OFFLINE`) with a 90s grace window — but "Offline" here means *not
  registered with Twilio/SIP*, **not** "the internet is down." Tooltip: "check your Twilio/SIP
  credentials."
- **`SidebarStatusModel` + `SidebarView`** render a status dot + label ("Connected / Reconnecting… /
  Offline") with a Motion-gated `pulse()` and a 1s `onTick()`.
- **There is no internet-reachability monitor** anywhere (`grep`: no `NetworkInterface` / `isReachable`
  / reachability probe in `src`). `AGENTS.md` flags "retry STUN if the network changes" as a known TODO.
- Call records (disposition + notes) auto-save **continuously to local SQLite** (`setOnCallLogAutoSave`)
  — so a logged outcome is already durable **without** internet (this de-risks the buyers' #1 fear).
- `MainWindow` already tracks **`callLive`** (the live-call gate used by the HUD/DND) — the call-state
  signal this feature needs.

**The two-offline problem:** shipping a separate "no internet" indicator next to the existing "not SIP
registered" one would make reps think the app is *double-broken*. They must be **merged**.

---

## 2. Locked product decisions

- **One merged readiness signal, not two.** Collapse internet + SIP into a single honest
  *"can I call right now?"* shown where the SIP status already lives (sidebar). Internet-down
  **absorbs** SIP state (no point saying "SIP reconnecting" when there's no internet to reconnect over).
- **Disable network actions, never the window — and the button is the signal.** Offline disables
  **Dial, Send SMS, power-dialer start**; the **button's own label flips to "Offline"** (the rep is
  looking at the button, not the sidebar dot), and **Dial runs a click-time pre-flight reachability
  check** so a dial fired in the ambiguous grace window **fails instantly with a clear reason instead
  of a 10s dead INVITE**. **Everything local stays fully usable** (read/copy a number, finish typing
  notes, save a disposition, scroll history). No scrim.
- **Call-state-aware, mid-call is sacred.** Offline **never** overlays or disables the **live-call or
  wrap-up** screen. **Hang Up and the notes/disposition fields stay reachable at all times.** A drop
  mid-call marks the call **dropped** with an **actionable "Call lost" note — lead name + number +
  one-tap redial** (now / when back online); the drop **routes into the dialer's pause-and-hold, never
  an auto-advance** (a drop is not a completed call — never skip a live prospect).
- **Grace window — no strobe, but bias jumpy on the Dial disable.** The status pill goes **amber at
  ~3s** and **Dial disables at ~3s too** (a lost dial on a 2s blip is cheap; a dial fired into a dead
  line is not); the loud red **"Offline"** label stays slow at **~8-10s** so it doesn't flap. The
  click-time pre-flight catches any dial in the ambiguous window. Recovery needs a short **stability
  check** so green/red never flap.
- **One message.** Reps don't care internet vs Twilio-unreachable vs DNS — show **"Calls & texts
  unavailable — connection lost."** (Detect the distinction internally for support logs only.)
- **Probe the real dependency — a TCP connect, not a ping.** Reachability is a **real TCP connect to
  the SIP/Twilio host** (2-3s timeout), *not* an HTTP-200 from anywhere and *not* `google.com`:
  **captive-portal café wifi** passes a NetworkInterface check and answers a google ping but fails the
  SIP handshake. A **single failed probe never disables anything** — only sustained failure through the
  grace window — so slow-but-working 1.5s wifi isn't murdered (false-offline).
- **Power dialer pauses, never auto-fires.** A drop mid-session **pauses in place and holds the cursor**
  on the current lead; recovery shows a **distinct inline panel (not a reflex-dismissed corner toast)**
  naming the lead — **"Resume with Maria Lopez (47 of 120)?"** — and after a **long outage (>2-3 min)**
  offers **"resume, or end session?"** It **never** auto-dials the next lead.
- **Recovery is automatic but never live-by-surprise.** On reconnect: re-register SIP, reconnect the
  SMS relay/poll, clear disabled states, dismiss the banner. The green **"Back online"** toast is
  **duration-gated** — a brief blip just flips the dot green (no toast); the toast only fires after a
  **long (>20-30s) outage** where trust was actually lost. **Never** auto-redial the lead, auto-resume
  the dialer, or reconnect a dead call.
- **Zero data loss is the hard floor.** Dispositions/notes already persist to local SQLite live; keep
  it that way (no state reset on offline). This is the requirement every buyer named.
- **No input-blocking scrim — and no dimmed card either.** The keyboard-stealing overlay *and* the
  >60s dimmed-idle card are **out of scope** (§8): the VA called the card "the scrim wearing a calmer
  hat." A dropped **inbound** ring is logged **"missed — connection lost"** so a callback never silently
  vanishes; a disabled **Send** with text typed reads **"will send when back online"** (the SMS queue
  itself stays P2).

---

## 3. Architecture & components (bottom-up)

### 3.1 Network probe (`telephony/` or `app/` — does I/O, off the FX thread)
- **`NetworkMonitor`** — a small scheduled prober (~5s): a cheap `NetworkInterface` "any non-loopback
  up?" gate, then a **real TCP connect** (`Socket.connect`, **2-3s timeout**) to the **SIP/Twilio
  host:port** — not an HTTP-200 (captive portals return 200s), not `google.com`. Emits a raw
  `boolean reachable`. Its own daemon scheduler (mirrors the SMS poller). The **probe is injectable**
  (a functional seam) so scheduling + debounce are tested without real sockets.
- **Click-time pre-flight** — `Dial` (and `Send`) run the **same reachability probe synchronously on
  click** (short timeout, off the FX thread): unreachable → fail instantly with "Offline — can't
  connect", never a 10s dead INVITE. This is the rep's real interrupt, not the sidebar dot.

### 3.2 Headless state machines (`ui/support/` — **unit-tested**, no JavaFX)
- **`ConnectivityHealth`** — mirrors `RegistrationHealth`: maps the raw `reachable` boolean →
  `ONLINE` / `UNSTABLE` / `OFFLINE` with a **grace window** (sustained loss before OFFLINE) and a
  **reconnect stability check** (sustained recovery before ONLINE, anti-flap). Time-injectable.
- **`CallReadiness`** — pure `resolve(ConnectivityHealth.State, RegistrationHealth.State) → Readiness`
  (`READY` / `RECONNECTING` / `OFFLINE`). Internet `OFFLINE` ⇒ `OFFLINE` regardless of SIP; internet
  `ONLINE` + SIP `REGISTERED` ⇒ `READY`; otherwise `RECONNECTING`. The single source of the dot.

### 3.3 UI (`ui/`)
- **`SidebarStatusModel`** — add `connectivity` + expose merged `readiness()` (via `CallReadiness`);
  the dot/label/tooltip render readiness, not raw registration. Icon: `bi-wifi` (ready) /
  `bi-wifi-off` (offline) alongside the dot.
- **`SidebarView`** — render the merged signal; Motion-gated pulse on `RECONNECTING`; a green
  **"Back online"** toast on `OFFLINE → READY`.
- **Readiness gate + the button as signal** — a `BooleanProperty callable` (true only when `READY`)
  that **Dial / Send / power-dialer-start** bind their `disable` to — *except* the live-call screen's
  **Hang Up** (always enabled). When not callable, the **button's own label flips to "Offline"** (the
  surface the rep is actually looking at); the slim non-blocking banner is secondary. A disabled
  **Send** with text typed reads **"will send when back online."**
- **Call-state guard** — `MainWindow` already knows `callLive`; offline disables/banner are
  **suppressed on the active-call + wrap-up views**; instead an **actionable "Call lost" note** (lead
  name + number + **one-tap redial**) appears, controls untouched, and the **dialer pause-and-hold**
  path owns the dropped lead (no auto-advance). A dropped **inbound** ring is logged **"missed —
  connection lost."**
- **Resume panel** — on recovery from a dialer pause, a **distinct inline panel** names the lead
  ("Resume with Maria Lopez, 47 of 120?"); after a long outage it also offers **"end session."** Not a
  corner toast.

### 3.4 Services (`services/`)
- **`PowerDialerService`** — a `pause(reason)` that holds the current position (reuse the existing
  PAUSED state) when readiness drops mid-session; resume stays human-triggered. `app/` wires
  readiness → pause.

---

## 4. UX / flow spec

### 4.1 The merged readiness dot (always visible, sidebar)
| Internet | SIP | Dot | Label | Icon |
|---|---|---|---|---|
| up | registered | green | **Ready to call** | `bi-wifi` |
| up | reconnecting | amber (pulse) | **Reconnecting…** | `bi-wifi` |
| **down** | any | red | **Offline — calls unavailable** | `bi-wifi-off` |

One dot, one truth: *can I call?* Internet-down outranks everything.

### 4.2 Idle + offline (after the grace window)
- Dial / Send SMS / Start-dialer **disabled with the button's own label flipped to "Offline"** (the
  rep's eyes are on the button, not the corner). A click in the ambiguous window hits the **pre-flight
  check** and fails instantly with "Offline — can't connect," never a dead 10s ring.
- A **slim non-blocking banner**; the rest of the app is fully usable (leads, notes, history). **No
  scrim, no dimmed card, no keyboard steal.**

### 4.3 Mid-call / wrap-up + offline (near-silent, sacred)
- **No banner takeover, no disables.** The call is marked **dropped**; an **actionable "Call lost"
  note** shows the **lead name + number + one-tap redial** (now / when back online); **Hang Up, mute,
  notes, disposition all stay live**; the dropped lead goes to **pause-and-hold, never auto-advance.**
  The outcome is already saving to local SQLite — nothing is lost.

### 4.4 Power dialer + offline
- **Pause in place**, hold the cursor on the current lead, show "Dialer paused — connection lost."
- On recovery: a **distinct inline panel** — **"Resume with {name} ({n} of {total})?"** — and after a
  **long outage (>2-3 min)** also **"end session?"**. Never auto-dial.

### 4.5 Recovery (the moment motion is welcome)
- Auto: re-register SIP, reconnect the SMS relay/poll, clear disables, dismiss the banner. The green
  **"Back online"** toast is **duration-gated** — a short blip just flips the dot green; the toast fires
  only after a **long (>20-30s)** outage. **Never** auto-redial / auto-resume.

---

## 5. Threading
- `NetworkMonitor` probes on its **own daemon scheduler** (off the FX thread); each result →
  `Platform.runLater` to update `SidebarStatusModel.connectivity`. The sidebar's existing **1s tick**
  recomputes `ConnectivityHealth.current()` + `CallReadiness` (grace/stability), so the dot, banner,
  and `callable` gate update on the FX thread only. The probe's socket timeout is short and never
  blocks the UI.

---

## 6. Phasing (each slice green via `./gradlew test`)

- **P0 — the honest signal + safe degradation (must-have):**
  1. **`NetworkMonitor`** (real TCP connect to the SIP/Twilio host, 2-3s timeout, injectable) +
     **`ConnectivityHealth`** (grace + anti-flap, single failed probe never trips; tested).
  2. **`CallReadiness`** merge (tested) → one sidebar dot ("Ready / Reconnecting / Offline") with the
     wifi/wifi-off icon.
  3. **The button is the signal** — disable Dial / Send / Start-dialer when offline, **flip the
     button's label to "Offline"**, and a **click-time pre-flight check** so a dial in the grace window
     fails instantly (no 10s dead INVITE); keep all local features live; slim non-blocking banner.
  4. **Call-state guard** — never disable/banner the live-call or wrap-up screen; Hang Up always live;
     **actionable "Call lost" note (name + redial)**; a mid-call drop routes to pause-and-hold, never
     auto-advance; a dropped **inbound** ring logged "missed — connection lost."
  5. **Power dialer pause-and-hold** + a **named, distinct resume panel** ("Resume with {name}…"),
     **"end session" after a long outage**; never auto-dial.
  6. **Recovery** — auto re-register + relay reconnect; **duration-gated** "Back online" toast (blip =
     dot-flip only); never auto-dial.
  - **Timing:** pill amber + Dial-disable at **~3s**; loud red "Offline" at **~8-10s**.
- **P1 — reassurance polish:**
  7. Subtle pulse on the pill; "last online at HH:MM" tooltip; internal diagnostics (internet vs
     Twilio vs DNS) for support logs; optional single soft sound on the *transition* to offline (a
     setting).
- **P2 / later:** offline SMS **send-queue** (compose offline → fires on reconnect; the disabled Send
  hints "will send when back online").

---

## 7. Testing
- **Tested (headless):** `ConnectivityHealthTest` (UNSTABLE within grace, OFFLINE after sustained loss,
  ONLINE only after stability window — anti-flap; time-injected, no sleeps), `CallReadinessTest`
  (internet-down absorbs SIP; up+registered = READY; up+reconnecting = RECONNECTING; truth-table
  exhaustive), `NetworkMonitor` scheduling/debounce via an injected probe (no real sockets).
- **Not tested:** the sidebar view, banner, dimmed card, FXML (JavaFX views).
- Coverage per `AGENTS.md` (ui support is the tested surface; ≥95% on the state machines).

---

## 8. Deliberately deferred / NOT building (YAGNI / buyer-vetoed)
- **The input-blocking full-window scrim** (the literal original ask) — 0/6 buyers; it eats notes,
  blocks Hang Up, and reads as "the app crashed." Replaced by the readiness signal + the button-level
  "Offline" + pre-flight.
- **The >60s dimmed-idle card** — the VA's call: "the scrim wearing a calmer hat." Once the buttons
  read "Offline" and the banner's up, an idle rep already knows; a centered card is friction nobody
  asked for. Dropped (was P1).
- **Any auto-redial / auto-resume** of dialing on reconnect — a rep put live with a prospect unbidden
  is a violation.
- **A second, separate "no internet" indicator** beside the SIP one — merged into one signal.
- **Animated full-screen offline takeover** — motion is for *recovery*, not for alarming the rep.
- **Cross-device / Salesforce sync queueing** — separate integration; local SQLite already persists.

---

## 9. Risks / traps (ranked — design against these)
1. **Lost notes / dispositions** — any state reset or block that drops a half-typed note destroys the
   data Marcus bills on. *Fix: never reset on offline; local SQLite already persists live.*
2. **Can't hang up a dead call** — if offline touches the call screen, the rep can't end a corpse call.
   *Fix: live-call/wrap-up are exempt; Hang Up always enabled.*
3. **Strobe-flashing on blips** — instant-on offline UI on every wifi roam = rage. *Fix: grace window
   (~8-10s) + reconnect stability check; pill ambers before it reds.*
4. **Power dialer losing its place / auto-firing** — *Fix: pause-and-hold, human resume, never
   auto-dial.*
5. **Two confusing "offline" meanings** — internet-offline vs not-SIP-registered. *Fix: one merged
   readiness signal; internet-down absorbs SIP.*
6. **The dot nobody looks at** — heads-down reps stare at the lead card + notes, not the sidebar.
   *Fix: the Dial/Send button itself flips to "Offline"; the dot is confirmation, not interruption.*
7. **Click-right-as-it-drops** — a dial fired in the grace window today rings dead for 10s. *Fix:
   click-time pre-flight reachability check → instant "Offline — can't connect."*
8. **Captive-portal false-green / slow-wifi false-red** — a café portal answers pings (false "online");
   a tight probe timeout murders slow-but-working wifi (false "offline"). *Fix: TCP connect to the
   SIP/Twilio host, 2-3s timeout, and only sustained probe failure (grace window) ever disables.*
9. **Reflex-dismissed resume prompt** — at hour 6 a corner toast gets fired blind. *Fix: a distinct
   inline panel naming the lead, with an "end session" escape after a long outage.*
- **Open question:** exact probe host:port (SIP proxy vs `api.twilio.com:443`) + intervals (~5s probe,
  ~3s amber/Dial-disable, ~8-10s red, ~20-30s toast gate). Settle against a real flaky-wifi/café session.
