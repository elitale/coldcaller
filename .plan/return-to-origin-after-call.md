# Return to origin screen after a call (bug fix)

> **Bug:** Calling from **Leads** or **Call History** opens the active-call screen; when the call
> ends and the rep closes wrap-up, the app jumps to the **Dialer** instead of returning to the
> screen the call was started from.
>
> Scope: `ui/` (`MainWindow`) + `app/` (`ColdCallingApp`) only. No domain/storage/service changes.
> **Plan only — do not implement.**

---

## 1. Root cause

The centre pane is swapped to show a call; the previous screen is never remembered, and every
post-call exit hard-codes the Dialer.

- **Entering a call** replaces the centre with no memory of the origin:
  - `MainWindow.showCallStarting(...)` → `root.setCenter(activeCallView)`
  - `MainWindow.showActiveCall(...)` → `root.setCenter(activeCallView)`
  - `MainWindow.showIncomingCall(...)` → `root.setCenter(incomingCallView)`
- **Leaving a call** always goes to the Dialer:
  - `ColdCallingApp.closeToDialer()` → `mainWindow.endActiveCall(); mainWindow.showDialer();`
  - `ColdCallingApp.finalizeAndClose(callId)` → … `mainWindow.showDialer();`
  - `ColdCallingApp.finalizeVoicemailAndAdvance(callId)` → … `mainWindow.showDialer();`
  - `MainWindow.showDialer()` → `root.setCenter(dialerView)`

There is **no "return target"** anywhere — `showDialer()` is unconditional, so a call dialled from
Leads/History (or reached from Messages on an inbound) always lands on the Dialer.

Navigation context that the fix can reuse:
- `MainWindow` owns the centre, the loaded view fields (`dialerView`, `leadsView`,
  `callHistoryView`, `messagesView`, `powerDialerView`, `settingsView`, `activeCallView`,
  `incomingCallView`), and `navigate(Destination)` (which sets the centre **and** the sidebar
  active state + refreshes). `NavSelectionModel.Destination` is the 1:1 enum for the nav screens.

---

## 2. Locked decisions

- **Remember the last non-call centre as the call's return target**, captured at the moment a call
  screen is shown. Restore it when the call closes. Fall back to the Dialer only when there is no
  captured origin (e.g. a call that started before any screen was shown).
- **Capture, don't guess.** The origin is whatever centre was showing immediately before the call
  screen replaced it — so it works uniformly for Leads, Call History, Dialer, Power Dialer, and an
  inbound call answered from Messages/anywhere.
- **Never capture a call screen as the origin** (so a power-dialer *advance* — which re-enters the
  call screen — does not overwrite the real origin with `activeCallView`).
- **Restore via `navigate(origin)`** so the **sidebar active state** and per-screen refresh are
  correct on return (not just the centre pane).
- **`MainWindow` owns it.** It holds the centre, the views, and the sidebar; `ColdCallingApp` only
  swaps its three `showDialer()` post-call calls for the new `returnFromCall()`.
- **Keep `showDialer()`** for any genuine "go to the dialer" need; only the *post-call* exits change.

---

## 3. The fix

### 3.1 `MainWindow` — capture + restore
- Add a field `private Parent callReturnView;` — the screen to return to after a call.
- **Capture** at the top of each call-entry method's existing `Platform.runLater`, *before*
  `root.setCenter(...)`:
  ```
  rememberReturnTarget();   // callReturnView = current centre, unless it is a call screen
  ```
  where `rememberReturnTarget()` sets `callReturnView = root.getCenter()` only when the current
  centre is **not** `activeCallView` and **not** `incomingCallView`. Add this to
  `showCallStarting`, `showActiveCall`, and `showIncomingCall`.
- Add a public, thread-safe **`returnFromCall()`**:
  ```
  public void returnFromCall() {
      Platform.runLater(() -> {
          Parent target = (callReturnView != null) ? callReturnView : dialerView;
          callReturnView = null;
          destinationFor(target)
              .ifPresentOrElse(this::navigate, () -> root.setCenter(target));
      });
  }
  ```
- Add a small reverse map **`Optional<Destination> destinationFor(Parent view)`** (1:1 with the
  view fields: dialer/leads/callHistory/messages/powerDialer/settings → the matching
  `Destination`; call screens → empty). `navigate(dest)` then restores the centre **and** the
  sidebar active state + refresh, reusing existing logic.

### 3.2 `ColdCallingApp` — stop hard-coding the Dialer
Replace the post-call `mainWindow.showDialer()` calls with `mainWindow.returnFromCall()`:
- `closeToDialer()` → `mainWindow.endActiveCall(); mainWindow.returnFromCall();`
- `finalizeAndClose(callId)` → … `mainWindow.endActiveCall(); mainWindow.returnFromCall();`
- `finalizeVoicemailAndAdvance(callId)` → … `mainWindow.returnFromCall();` then `advance()`
  (origin for a power-dialer run is the Power Dialer screen, so this returns there before the next
  auto-dial re-enters the call screen — correct, see §4).

(Method name `closeToDialer()` becomes a slight misnomer; rename to `closeAfterCall()` for clarity.)

---

## 4. Edge cases & behavior

- **Dial from Dialer** → origin = `dialerView` → returns to Dialer (unchanged).
- **Dial from Leads / Call History** → origin = that view → returns there (the fix).
- **Inbound call** answered while on Messages/Leads/etc. → origin = that screen → returns there
  after hang-up. (Today it also dumps to the Dialer.)
- **Power dialer** → origin = `powerDialerView`. After wrap-up/voicemail-advance it returns to the
  Power Dialer screen, then the auto-advance dials the next lead (which re-shows the call screen).
  Net: brief Power-Dialer frame between calls — acceptable, arguably correct. *Optional refinement:*
  when a power-dialer session is `Running`, skip the navigate and let the next dial paint the call
  screen directly (avoids the flash). Low priority.
- **User navigates away during a live call** (clicks a sidebar item mid-call): the centre changes,
  but `callReturnView` was captured at call start, so `returnFromCall()` still restores the
  original origin. Acceptable; documented as a minor edge.
- **No origin captured** (call before any screen) → falls back to the Dialer.

---

## 5. Phasing
Single small slice:
1. `MainWindow`: `callReturnView` + `rememberReturnTarget()` (wired into the 3 call-entry methods)
   + `returnFromCall()` + `destinationFor(...)`.
2. `ColdCallingApp`: swap the 3 `showDialer()` post-call calls for `returnFromCall()`
   (+ rename `closeToDialer` → `closeAfterCall`).
3. `./gradlew build` green.

---

## 6. Testing
- **JavaFX navigation — not unit-tested** per repo convention (this is view wiring). The
  view→`Destination` mapping is trivial and total.
- *Optional* tiny headless helper `CallReturnTarget` (holds last-non-call destination, ignores call
  screens) if we want a unit test for the "don't capture a call screen / fallback to Dialer" rules;
  likely overkill for a one-field memory.
- **Manual verification matrix:**
  | Start from | Call → end → close | Expect |
  |---|---|---|
  | Dialer | manual call | Dialer |
  | **Leads** | manual call | **Leads** |
  | **Call History** | manual call | **Call History** |
  | Messages (answer inbound) | inbound call | Messages |
  | Power Dialer | run | Power Dialer (then next auto-dial) |
  | any | **failed/cancelled** call | back to the origin (via `closeAfterCall`) |

---

## 7. Risks / open questions
- **`navigate(MESSAGES)` side effects on return** — it calls `messagesController.refresh()` +
  `markMessagesSeen()`. Returning to Messages after a call will refresh/clear the unread dot; that's
  acceptable (you're looking at it). Confirm no surprise.
- **Power-dialer flash** (§4) — decide whether to ship the optional "skip navigate while a session
  is Running" refinement now or later.
- **Thread-safety** — `callReturnView` is read/written only inside `Platform.runLater` blocks (FX
  thread), so no extra synchronization is needed; keep all access on the FX thread.

---

## 8. Out of scope
- Persisting the return target across app restarts (in-memory only).
- A full navigation history/back-stack (this is a single "return to where the call started", not a
  general back button).
- Changing power-dialer advance semantics beyond the optional flash refinement.
