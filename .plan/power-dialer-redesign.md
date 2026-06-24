# Power Dialer — Redesign & Reconfiguration

> Status: **P0 COMPLETE** + **default "All Leads" target** (§9) — `./gradlew build` + tests green. P1 (operator speed) next.
> Goal: make the Power Dialer *simple, trustworthy, and resumable*. Pick a list → start → it dials the right people → resume where you left off.
> Validated by the `buyer` (ICP lie-detector, 6 buyers) and `va` (daily operator, 5 SDRs/VAs/managers) agents. Both verdicts are folded in below.
>
> **Locked decisions (user):** (1) Delete removed from Power Dialer — managed on Leads. (2) **No "Start over" button** — a single primary button only: **Start** on a fresh list, **Resume from #N** on a partial one; both begin at the first pending lead. (3) Disposition buttons stay **P1**. (4) Memory = last-used list + resume position only (no per-list resume/restart choice — there is no restart).

---

## 1. Why this exists (the problem)

The current Power Dialer screen fails its one job — "pick a list, start dialing" — on first run:

| # | Bug / trap | Evidence (code) |
|---|---|---|
| 1 | **Empty-list trap.** "+ New" creates a 0-lead list. There is **no way to add leads from this screen**, so a fresh list can never start. | `PowerDialerController.onNewList()` → `PowerDialerService.createCallList(name)` saves `NewCallList(name, empty)` with zero entries. |
| 2 | **Start throws a raw error on an empty list.** The obvious flow (New → Start) dead-ends. | `PowerDialerService.start()` filters `!entries().isEmpty()` → `Result.err("...not found or empty")`, surfaced as an `Alert`. |
| 3 | **Delete is a no-op lie.** The button does nothing. | `PowerDialerController.onDeleteList()` only calls `refresh()`; comment says "best-effort; ignore errors". `CallListService.delete()` exists and works but the controller has no `CallListService`. |
| 4 | **No resume / re-dials from the top.** Per-lead status (`pending/dialed/skipped`) **is** persisted, but `start()` always begins at position 0, re-calling people already reached. | `Session` starts `position = 0`; `call_list_leads.status` written by `markCurrentEntry` but never read back to resume. |
| 5 | **Disconnected authoring.** Leads only enter a list on the separate **Leads** screen (`CallListService.addLeads`). Nothing on the Power Dialer says so. | `LeadImportService` / Leads workbench lists rail use `callListRepo.addLeads`. |
| 6 | **Idle screen looks broken.** Three `0 / 0 / —` stat tiles + "Up Next" render while idle; "Select a list to begin" is the only guidance. | `power-dialer-view.fxml` center column. |

---

## 2. Agent verdicts (condensed — full convergence)

**Both panels independently reached the same conclusions.** Quotes are representative.

### Buyer panel (will they trust / pay?)
- **5 of 6 distrust on first run; 4 of 6 would churn or never convert.** "New→Start→error spent my goodwill in 90 seconds." (Carlos) · "A no-op Delete button is the kind of thing my RevOps lead screenshots." (Jake)
- **Re-dial-from-top is an active churn cause.** "A number that re-dials its whole list every morning gets flagged inside a week… that's a refund, not a ticket." (Marcus) · corrupts connect-rate metrics (Lisa) · TCPA/compliance exposure for committee buyers (Jake/Lisa).
- **Power Dialer should consume lists, not author them.** 4/6 explicit; Carlos OK only if "New" forces inline lead-add.
- **Resume must be explicit + auditable:** "47 of 200 dialed — Resume / Start over," Resume default, skip already-dialed, remember the choice per list. **Creepy = auto-dialing on app open / before the operator picks their number.**

### Operator panel (will they keep using it 7 h/day?)
- **Empty-list trap → rage-quit in ~30–45 s + a first-run support ticket.** Remove "+ New" here; **disable** Start (with tooltip) instead of erroring; empty state routes to Leads.
- **Resume is the #1 trust feature.** "Resume at 47 of 200 = I don't re-dial 46 people." Non-blocking banner: **"You dialed 140 of 210. [Resume from #141] [Start over]"**, default Resume, skip `dialed`, remember per list.
- **Keep this screen for *driving*, not data entry.** Mixing lead CRUD bloats the screen they live in.
- **Always-on during a session:** current lead, **which number I'm dialing FROM** (local presence — currently missing), one-click disposition + auto-advance, **Next Lead**, compact progress, persistent **Up Next**, inline note.
- **One click = disposition + advance**; `1–6` disposition hotkeys are "the feature I tell other SDRs about." Never auto-advance out from under a half-typed note.
- **Cut:** three big stat tiles → one line; fake Delete; the tiny hint text.

---

## 3. Locked product decisions

1. **Power Dialer is a pure *consumer* of lists.** It does **not** create or delete lists. Authoring + deletion live on the **Leads** screen (`CallListService` already supports `create` / `delete` / `addLeads` / `removeLead`).
   - Remove **"+ New"** and **"Delete"** from the Power Dialer.
   - Empty state (no lists, or selected list has 0 leads) → guided message + a **"Build a list on Leads →"** button that navigates to the Leads screen.
2. **Start is never a raw error.** If the selected list has 0 pending leads, **Start is disabled** with a tooltip ("This list has no leads to dial — add leads on the Leads screen"). 
3. **Resume by default; never silently re-dial reached leads.** The single primary button always starts at the first `PENDING` entry and skips `DIALED`/`SKIPPED`. **There is no "Start over"** — a fully-dialed list is complete (re-run by rebuilding/re-importing on Leads).
4. **Remember last-used list + position** across navigations and app restarts (persist last list id; per-lead status already persisted).
5. **No automatic dialing.** Resume/Start is always operator-initiated. The app never dials on open. (Compliance gate from Jake/Lisa.)
6. **Idle vs in-session split.** Live tiles / Pause / Stop / disposition / "Up Next-as-live" are hidden until a session is running; the idle screen shows a list picker + a readiness line + one big primary button.
7. **Show all lists with progress**, auto-select the last-used list on open.
8. **Single source of list truth:** the Power Dialer reads lists through `CallListService` (not its own `createCallList`); `PowerDialerService.createCallList` is removed.

> **Carve-out (KISS/YAGNI):** we are **not** collapsing Leads + Power Dialer into one "Campaign" object (Carlos's suggestion). It's a larger IA change; the empty-state→Leads link solves the first-run trap at a fraction of the cost. Revisit only if list-building friction persists.

---

## 4. Target UX

### 4.1 BEFORE start (idle) — "choose & launch"

```
┌─ Power Dialer ─────────────────────────────────────────────┐
│  Call Lists                     │   Texas Q3 Outreach       │
│  ┌───────────────────────────┐  │   80 leads · 47 dialed    │
│  │ ▸ Texas Q3 Outreach       │  │   33 remaining            │
│  │     33 of 80 left         │  │   last dialed 2h ago      │
│  │   NY Warm Leads           │  │                           │
│  │     0 of 120 left  ✓done  │  │   Dialing from            │
│  │   Empty Demo List         │  │   [ +1 916 35x xxxx  ▾ ]  │
│  │     no leads — add on      │  │                           │
│  │     Leads →                │  │   ┌─────────────────────┐ │
│  └───────────────────────────┘  │   │  ▶  Resume from #48 │ │  ← single primary (big)
│                                 │   └─────────────────────┘ │
│  (no +New / Delete / Start over)│                           │
│                                 │                           │
│                                 │   Up Next                 │
│                                 │   1. Jane Cole · Acme     │
│                                 │   2. Raj P. · Globex      │
└─────────────────────────────────┴───────────────────────────┘
```

- Primary button label is **"Resume from #N"** when the list has progress, else **"Start dialing"**.
- If the selected list has **0 leads** → readiness line becomes "No leads yet" and the primary button is replaced by **"Build a list on Leads →"**.
- **Hidden while idle:** live Dialed/Connected/Remaining tiles, Pause, Stop, Next Lead, disposition bar.

### 4.2 AFTER start (in-session) — "drive"

```
┌─ Power Dialer ─────────────  34 / 210 · 12 connected  ──────┐
│  (list rail collapses/dims)     │   Jane Cole               │
│                                 │   +1 415 555 0132  · Acme │
│                                 │   ☎ from +1 916 35x ····  │  ← local-presence number
│                                 │   last note: "cb Tue 2pm" │  ← prior-call note (P2)
│                                 │                           │
│                                 │  [1 Interested] [2 Not]   │
│                                 │  [3 Callback]  [4 VM ]    │  ← disposition + auto-advance
│                                 │  [5 No answer] [6 DNC]    │
│                                 │  Note: [____________]     │
│                                 │                           │
│   ▮▮ Pause    ■ Stop            │   →  Next Lead            │
│                                 │   Up Next: Raj P. · Globex│
└─────────────────────────────────┴───────────────────────────┘
```

- **One compact progress line** in the header (`34 / 210 · 12 connected`) replaces the three tiles.
- On a **connected** call: no auto-advance (rep controls the talk). Clicking a disposition (or `1–6`) **saves outcome + note, then advances** in one action. A standalone **Next Lead** still exists for skip-without-disposition.
- On **no-answer/busy/failed**: auto-advance after the configured delay (unchanged).

### 4.3 Target flow (operator's words)
- **Before:** open app → lands on last list, primary button focused as **Resume** ("33 of 80 left") → press **Enter** → resumes at #48. *1 keystroke to resume yesterday.*
- **In session:** lead rings → talk → press **1** (Interested) → note saved, advances to #49. *1 keystroke per disposition.*

---

## 5. "Remember the last call" / resume — spec

**Data we already have:** `call_list_leads.status ∈ {pending, dialed, skipped}` is persisted; `findById`/`findAll` load entries with status. We only need to (a) persist the last-used list id and (b) read statuses back.

| Concept | Definition |
|---|---|
| **remaining / pending** | `entries.filter(status == PENDING).count()` |
| **dialed** | `entries.filter(status != PENDING).count()` |
| **resume position** | index of the first entry with `status == PENDING` (`-1` ⇒ list complete) |
| **last-used list** | settings key `power_dialer.last_list_id` (string), written on every `start`/`resume`/list-select |

**Behaviour**
- On navigate to / open Power Dialer: load lists, auto-select `last_list_id` (or first), compute readiness.
- **Start/Resume** (single button): start a session positioned at the first `PENDING` entry; while advancing, **skip** any non-`PENDING` entry (`while position<size && status≠PENDING: position++`). Robust against interleaved skips. A fresh list (all pending) ⇒ first-pending is `0` ⇒ button reads **"Start dialing"**; a partial list ⇒ button reads **"Resume from #N"**.
- **Never auto-dial on app open.** Start/Resume is a button press.
- List with **0 pending** (all dialed): primary button is **disabled** and reads **"List complete"** (no silent re-dial, no Start over).

---

## 6. Changes by layer (with TDD)

### domain/ (pure)
- Small value **`ListProgress(int total, int dialed, int pending, int resumeIndex)`** with static `of(CallList)` (`resumeIndex` = first PENDING index or `-1`). Co-locate a test. Keeps counting logic out of the UI and the service.
- **No `PowerDialerStartMode`** — the single `start` path is resume-aware; no mode needed.

### storage/
- **No changes.** No "Start over" ⇒ no `resetEntries`. Entries already carry status; no new read queries.

### services/
- **`PowerDialerService`**
  - Remove `createCallList(...)` (authoring moves out).
  - `start(CallListId)` becomes **resume-aware**: build session, set `position = ListProgress.of(list).resumeIndex()`; if `-1` (no pending) → `Result.err` "List complete"; if no entries → `Result.err` "empty".
  - `dialCurrent`: **skip non-`PENDING` entries** (snapshot status) before dialing so resume never re-dials reached leads.
  - On successful start: persist `power_dialer.last_list_id` via `SettingsService`.
  - Add `Optional<CallListId> lastUsedListId()` (reads the setting).
  - Keep existing connected-call rule (no auto-advance on answered; manual/disposition advance).
  - Tests: resume-from-first-pending, skip-dialed-on-advance, last-list persisted, 0-pending ⇒ "complete" err. (Service ≥90%.)
- **Disposition (P1):** `disposition(CallListEntry/CallId, Disposition)` that records the outcome on the current `calls` row + marks entry `DIALED` + advances. Requires the current `callId` (already available via `notifyCallAnswered(callId)`).

### ui/
- **`power-dialer-view.fxml`** — restructure into idle vs in-session regions (toggle `visible`/`managed`); remove `newListBtn`/`deleteListBtn`; add readiness line, primary `startBtn` (dynamic label), secondary `startOverBtn`, `dialingFromLabel`/number selector, disposition bar (P1), header progress label. Persistent `Up Next`.
- **`PowerDialerController`** — inject `CallListService` is **not** needed (authoring removed); inject a **nav callback** `Runnable onGoToLeads` (wired by `MainWindow` to `navigate(LEADS)`). Replace `onNewList`/`onDeleteList` with `onGoToLeads`. Add resume/start-over handlers, readiness rendering, idle/session view-state toggling. Keep ≤250 lines — extract a headless **`PowerDialerReadiness`** helper (counts + button label + enabled state) into `ui/support` and unit-test it.
- **`MainWindow`** — wire `onGoToLeads`; ensure `navigate(POWER_DIALER)` refreshes readiness + auto-selects last list. (`navigate(LEADS)` exists.)

### Headless, unit-tested (per repo convention)
- `ui/support/PowerDialerReadiness` (label/enabled/empty-state logic), `domain` `ListProgress`/`firstPendingIndex`, `services` resume/skip/start-over, `storage` `resetEntries`. JavaFX FXML/controller/cells are **not** unit-tested (manual smoke).

---

## 7. Phased plan

### P0 — make it work + trustworthy (the user's explicit asks)
1. **Consume-only lists.** Remove "+ New" / "Delete" from Power Dialer; remove `PowerDialerService.createCallList`. Empty state → **"Build a list on Leads →"** nav button.
2. **Start never errors.** Disable Start when selected list has 0 pending; tooltip explains why.
3. **Show all lists + select**, each row with `X of N left`; auto-select last-used list.
4. **Resume / remember last call.** Resume-aware `start` (first-pending start, skip-dialed on advance), persist `last_list_id`, auto-select last-used list on open. Readiness line "**80 leads · 47 dialed · 33 remaining**" + single button "**Resume from #48**". No Start over.
5. **Idle vs in-session split.** Hide live tiles/Pause/Stop/Up-Next-live while idle; show readiness + primary button.
6. **Verify the authoring path works end-to-end** (Leads → create list → add leads → appears in Power Dialer → select → Resume/Start). Fix any gap found.

### P1 — operator speed
7. **Dialing-FROM number visible** in-session (local presence).
8. **One-click disposition + auto-advance** with `1–6` hotkeys; standalone Next Lead retained; never advance over a half-typed note.
9. **Core hotkeys:** `Enter`=next, `Esc`=hang up, `Space`=answer, `P`=pause/resume, `N`=notes, `V`=voicemail.
10. **Persistent Up Next** (visible before start too).
11. **Collapse 3 stat tiles → one progress line.**

### P2 — polish
12. **Prior-call note** on the lead card the instant it appears.
13. **List row health** (`pending` vs `dialed`) + **last-dialed-at** timestamp; multi-client grouping/labeling of lists.
14. Number-rotation visibility on the before screen.

---

## 8. Deliberately NOT doing (YAGNI / KISS carve-outs)
- **No "Campaign" merge of Leads + Power Dialer.** Empty-state→Leads link solves the trap far cheaper.
- **No inline lead-entry / CSV import on the Power Dialer.** Authoring stays on Leads (single source of truth — both panels agreed).
- **No auto-dial on open.** Compliance + trust; resume is always a click.
- **No new migration / no new query** — `call_list_leads.status` already exists and loads with entries.
- **No "Start over"** — a completed list is done; re-run by rebuilding on Leads. (User decision.)
- **No interleaved-skip UI** — advance simply skips non-pending; no per-lead manual reordering yet.

---

## 9. Follow-up — default "All Leads" target (DONE)

> User: *"it should have a default list called all lead list just like [the Leads screen] and use that as the initial list."* On first run there are leads but no saved lists, so the Power Dialer selector was empty/unusable.

**Decision:** mirror the Leads screen's **virtual "All Leads"** (a `Optional.empty()` filter scope, *not* a real `call_lists` row). The Power Dialer selector is now a sealed **`DialTarget`** — `AllLeads(leadCount)` | `OneList(CallList)`. "All Leads" is always the **first** row and is **auto-selected by default** (first run, or after it was the last-used target).

**Why virtual, not a real list:**
- The Leads rail already renders its own virtual "All Leads"; creating a real `call_lists` row named "All Leads" would show a **duplicate** there and need add/delete **sync** as leads change.
- Avoids a schema/migration and a settings-tracked system-list id.

**Carve-out (accepted):** the synthetic pool carries **no persisted per-entry status**, so "All Leads" has **no cross-restart resume** (in-session pause/resume still works) and no Resume/Complete readiness — it always reads **"Start dialing"**. Saved lists keep full resume. This matches the "All Leads = a live view, not a saved campaign" mental model.

**Changes by layer:**
- `domain/` — `ListProgress.allPending(int)` (all-pending pool projection) + tests.
- `services/PowerDialerService` — `startAllLeads()` builds an in-memory `CallList` from `leadRepo.findAll()` (synthetic id `Long.MAX_VALUE`, entry id `0`); `Session.synthetic` flag makes `markCurrentEntry` a **no-op** (no DB rows to write); `countAllLeads()`; `lastUsedAllLeads()` (sentinel `KEY_LAST_LIST="all"`). Tests cover dial-first, no-leads error, token persist, no-entry-write, count.
- `ui/support/DialTarget` — sealed target with `title()` / `progress()` / `readiness()` / `selectorLabel()`; headless, unit-tested.
- `ui/controller/PowerDialerController` — `ListView<DialTarget>`; `reloadLists` prepends `AllLeads(count)`; `selectTarget` defaults to All Leads when no real last-used list; `onStart` switches `AllLeads → startAllLeads()` / `OneList → start(id)`.
- `ui/controller/PowerDialerCells` — `target()` renders `DialTarget.selectorLabel()`.

**Not done:** DNC filtering for the All Leads pool is **unchanged from saved-list behaviour** (the dialer doesn't filter DNC at this layer today) — out of scope for this follow-up; track separately if compliance wants it.

## 9. Test plan
- `domain`: `ListProgress`/`firstPendingIndex`, `PowerDialerStartMode`.
- `storage`: `resetEntries` (in-memory SQLite).
- `services`: resume-from-first-pending, skip-dialed-on-advance, start-over reset, last-list persistence, 0-pending ⇒ complete, disposition+advance (P1).
- `ui/support`: `PowerDialerReadiness` (labels/enabled/empty).
- `./gradlew build` + `./gradlew test` green before each phase is marked done. Controllers ≤250 lines (extract helpers).

## 10. Resolved decisions (locked 2026-06-25)
1. **Delete** — removed from Power Dialer; list deletion lives on the Leads screen. ✅
2. **"Start over" — removed entirely.** One primary button only: **Start** (fresh) / **Resume from #N** (partial), always beginning at the first pending lead. A fully-dialed list shows a disabled **"List complete"**. No status-reset path. ✅
3. **Disposition buttons** — stay **P1** (agreed). ✅
4. **Memory** — last-used list + resume position only (per-lead status already persisted). No per-list resume/restart choice (there is no restart). ✅
