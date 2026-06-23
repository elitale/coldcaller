# Plan — Recent Call Detail Panel (Number Context)

> Status: **Implemented** (Phases A–D). Clicking a Recent Calls row opens a non-blocking right-docked detail panel. Container changed from modal to in-window side panel via `BorderPane.setRight()`.
> Reviewed by: `buyer` agent (6-persona ICP panel). Verdict folded in below.

---

## 1. The one decision that matters: container

**Today:** `NumberDetailDialog` is an `APPLICATION_MODAL` `Stage` (460×560 ScrollPane).

**Change:** convert to a **non-blocking slide-over side panel** docked on the right of the main window. It opens on row-click, **updates live as the user arrows through Recent Calls rows**, never blocks the dialpad, dismissible with `Esc` but does not *demand* dismissal.

**Why (buyer panel, 6–0):** A modal blocks the `dial → talk → disposition → next` loop, which *is* the product. The daily power users (200 calls/day) would route around it and resent it. A side panel with the same contents *accelerates* the loop. "The container is the decision, not the contents."

> If a full slide-over is too large for one pass, acceptable interim: keep the dialog non-modal (`WindowModality.NONE`) and reposition it beside the main window. But the side-panel-in-main-window is the real target.

---

## 2. The 2-second post-call loop (design the panel around this)

Buyer consensus on the **#1 action after a call**: *set the disposition + drop a one-line note, then advance to next.* Not stats. Not "best time to call."

So the panel's **top zone = action zone**, keyboard-reachable, completable in <2s:

```
┌────────────────────────────────────────────┐
│  Jane Doe · Acme Inc                    ✕   │  ← name (or number), close (Esc)
│  +1 415 555 0134  📋     🇺🇸 9:12am local   │  ← number + copy, timezone label
│  ────────────────────────────────────────  │
│  [ Call ]  [ Message ]  [ Edit / +Add ]    │  ← quick actions (C / M / E)
│  ────────────────────────────────────────  │
│  Disposition:  ( Interested ) ( Callback )  │  ← chips, one-keystroke each
│                ( Not int. ) ( Voicemail )…  │
│  Note: [ ________________________ ]  ↵     │  ← one-line note, Enter to save
└────────────────────────────────────────────┘
        ↓ reference material below the fold ↓
  Stats strip · Combined timeline · per-call notes
```

---

## 3. Scope — prioritized (from buyer P0/P1/P2/Cut)

### P0 — build first
1. **Container → non-blocking side panel.** Highest leverage. Without it the rest is lipstick. **Cost note:** `MainWindow` today only swaps the *center* pane via `showCenter(Parent)`; there is **no right-dock region yet**. Phase A's real work is the layout refactor to host a right slide-over, not the contents.
2. **Inline disposition set/edit on historical calls.** #1 post-call action. **Correction (verified in code):** the repository plumbing already exists — `CallRepository.findById(CallId)` + `CallRepository.update(Call)` are present. We only need new **service** methods (`CallService.updateDisposition(CallId, …)`, `updateNotes(CallId, …)`) that read-modify-write the `Call` record and bump its existing `updatedAt`. **No new repo method and no migration are required for the basic edit.** A dedicated audit trail (`changed_by` / `disposition_changed_at`) is a *multi-seat* concern and is **deferred** (see §7 Q3 and §8 Edge cases) — it carries a large blast radius (new `Call` fields → `NewCall` → row mapper → update SQL → every `Call` construction site).
3. **Quick actions + per-call note:** Call (`onDial`), Message (`messagesController.openConversation`), Copy number, **Add to leads** for unknown inbound leads (`LeadService.save`), one-line note field (persisted via `CallService.updateNotes(CallId, …)`).
4. **Keyboard shortcuts:** `C` call · `M` message · `E` edit · `Esc` close · digit/letter hotkeys per disposition. Cheap, disproportionate power-user love, signals "modern app."

### P1 — next
5. **Combined call + SMS timeline.** Interleave `CallService.findByRemoteNumber` + `SmsService.findThread` chronologically. Most likely to earn word-of-mouth (Priya). P1 only because it's two-source plumbing.
6. **Auto timezone / local-time label** — inferred from the number's country (`CountryLookup.byE164` → `Country.zone()`), zero config. Tiny label e.g. "9:12am local". High value for offshore callers. The moment it needs config, cut it.

### P2 — later, lightweight
7. **Slim stats strip:** `last contacted` + `total calls` + `total talk time` only. Small, below the action zone.

### Cut (this scope)
- **"Best time-of-day to reach" and per-lead "connect rate."** Statistically meaningless at single-lead sample sizes; flagged as noise by 3 buyers. Belongs in aggregate analytics later, not here.
- **"Set callback reminder" button — UNLESS the callback resurfaces in the work queue.** A reminder that doesn't resurface is theater. Either scope it as *capture here → resurface in dialer/queue at the promised time*, or omit the button until that loop exists.

---

## 4. Codebase mapping (what we reuse vs. build)

| Need | Reuse | Build |
|---|---|---|
| Header (number, flag, country, dial code) | `NumberDetailDialog.header()`, `CountryLookup`, `CountryCatalog.ALL`, `FlagImages` | — |
| Local-time label | `Country.zone()` + `DateTimeFormatter` | tiny label |
| Lead card | `NumberDetailDialog.leadCard()`, `LeadService.findByPhone` | — |
| Quick Call | `onDial` (already wired) | button + `C` accel |
| Quick Message | `MessagesController.openConversation(PhoneNumber)` (already added) | button + `M` accel |
| Add to leads | `LeadService.save(NewLead)` | small inline form/dialog + `E` |
| Edit lead | `LeadService.update(Lead)` | inline edit affordance |
| Copy number | — | clipboard write |
| Call history list | `NumberDetailDialog.callsSection/callRow`, `RecordingPlayer` | — |
| SMS in timeline | `SmsService.findThread(PhoneNumber)` | merge + render SMS rows |
| **Disposition edit (historical)** | `CallRepository.findById` + `CallRepository.update` (both already exist) | new `CallService.updateDisposition(CallId, …)` (read-modify-write `Call`, bumps `updatedAt`) |
| Per-call note (historical) | `CallRepository.update` | new `CallService.updateNotes(CallId, …)` |

> ⚠️ **Do NOT reuse `CallService.setDisposition(String callId, …)` / `setNotes(...)` for the panel.** Verified in code: those mutate an **in-memory `ActiveCall`** keyed by the **SIP Call-ID** (a `String`) for an *in-flight* call only. Historical calls aren't in `activeCalls`, and the persisted `Call.id()` is a `CallId` (DB row id), a different identifier — so those setters would be silent no-ops here. The panel must go through `findById(CallId)` → rebuild → `update(Call)`.

**Schema note:** No migration is needed for the basic disposition/note edit — `calls` already has `updated_at` and a nullable `disposition`. Only add a migration if/when the multi-seat audit trail is actually built.

---

## 5. Architecture / AGENTS.md compliance

- All DB reads off the FX thread via `CompletableFuture.supplyAsync(...)` → `Platform.runLater(...)` (pattern already in `NumberDetailDialog.load()`).
- Loading, empty, and error states for every async section (history, timeline, save actions).
- Disposition/note write-through must update the **same** `Call` record (no data silo) — managers need it to be the syncable source of truth.
- New domain/service work is TDD: service test for retroactive disposition/notes edit (read-modify-write via `update`, asserts `updatedAt` bumped), formatter tests for timeline merge ordering, and a guard test for non-E.164 numbers.
- Keep controllers thin: panel is a UI component; persistence goes through `CallService` / `LeadService` only.

---

## 6. Suggested phasing

- **Phase A (P0):** side-panel container + quick actions + copy + add-to-leads + keyboard shortcuts. (UI-only; reuses existing services.)
- **Phase B (P0):** historical disposition + note edit via new `CallService.updateDisposition/updateNotes` (reuses existing `CallRepository.findById`/`update`; no migration). (Service + UI; TDD.)
- **Phase C (P1):** combined call+SMS timeline + auto timezone label.
- **Phase D (P2):** slim stats strip.

Each phase ends green on `./gradlew build` + new tests.

---

## 7. Open questions for the user

1. **Side panel inside the main window** (target) vs. **non-modal floating window** (faster interim)? Recommend the in-window side panel. (In-window costs a `MainWindow` layout refactor — there's no right region today.)
2. Is **historical disposition editing** in scope now (Phase B), or should the first pass be UI-only (Phase A) reusing existing read-only history?
3. **Audit trail — defer?** Single-operator desktop app today, so `changed_by` is meaningless and `disposition_changed_at` duplicates the existing `updated_at`. Recommend **deferring the audit columns** (they're a multi-record blast radius) and relying on `updated_at` for now. Confirm?

---

## 8. Edge cases & data-quality traps (found during code review)

1. **Non-E.164 remote numbers crash `PhoneNumber`.** `findByRemoteNumber`, `findThread`, and `findByPhone` all take a `PhoneNumber`, whose compact constructor throws `IllegalArgumentException` for anything not matching `\+[1-9]\d{1,14}`. Inbound calls from private/unknown/short-code/malformed caller-IDs will blow up the panel on open. **Guard:** wrap construction; if invalid, show the raw string read-only with actions (Call/Message/Add) disabled, mirroring the existing `openMessageThread` try/catch.
2. **Auto-disposition is misleading.** `CallService.persistCallRecord` defaults a normal hung-up call (`reason == "bye"`) to **`NotInterested`** via `mapReasonToDisposition`. So historical dispositions the panel shows are often an *auto-guess*, not a real outcome. This strengthens the case for retroactive editing, and the panel should treat a shown disposition as editable rather than authoritative. (Optional: visually distinguish auto-set vs user-set — but there's no flag for it today, so YAGNI unless asked.)
3. **`Failed` disposition carries a `reason` string.** `CallDisposition.Failed(reason)` isn't a simple toggle. The user-selectable disposition chips are the 7 non-`Failed` cases; don't render `Failed` as a settable chip.
4. **"Add to leads" does not back-link history.** Existing `Call` rows store `leadId = Optional.empty()`. Saving a new lead won't retroactively populate `leadId` on past calls, so `findByLead` still misses them. The panel itself is fine (it queries by `remoteNumber`), but don't promise CRM/lead-history linkage of past calls without a back-fill step. (Defer back-fill — YAGNI.)
5. **Timeline timestamp normalization.** `Call` orders by `startedAt`; `SmsMessage` by `sentAt`. The merge must map both to a common `Instant` key before sorting, and decide tie-breaking when a call and text share a second.
6. **Live updates while a call is active.** If the panel is open on a number that's currently in an active call, the in-memory `ActiveCall` disposition/notes won't be in the persisted history yet. Decide whether the panel refreshes on `onCallEnded` (it should — `MainWindow.refreshRecentCalls()` already fires there; the open panel should re-query too).
```