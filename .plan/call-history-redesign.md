# Call History Redesign — never miss a callback, see who you're calling

> Goal: turn Call History from a flat, identity-less table into a cold-caller workspace whose
> reason to exist is **"who do I owe a callback, and is it due?"** — with rich, lead-aware rows
> (inspired by the dialer's Recents) for the second job, **"what happened last time?"**
>
> Scope: mostly `ui/` + a thin slice of `services/`/`storage/` query work. **No schema migration**
> for P0 (the callback timestamp is already stored). **Plan only — do not implement.**

---

## 0. Buyer-agent validation (verdict that shaped this plan)

Stress-tested with the `buyer` agent (6 simulated cold-calling buyers/operators). The decisive
line:

> "A prettier two-line row helps 'what happened last time.' It does **nothing** for 'who do I owe
> a callback' unless you build callbacks-due surfacing as a first-class thing. Ship rich rows and
> skip callbacks and you've made a beautiful log that still feels weak."

Key rulings:
- **Callbacks-due is THE feature**, and only if it has a **real due date** the rep picks (not a
  cosmetic chip). Jake: *"a callback with no date and no reminder is theater — reps keep it in
  their head and drop it anyway."* Surface **Due today / Overdue pinned above the list**.
- **Per-lead rollup is the default; per-call is the source of truth underneath.** 5 calls to one
  prospect = 1 row ("4 calls · last Tue · Interested"), expandable to the per-call timeline. Never
  let the rollup *replace* per-call data (Marcus's audit, Jake's CRM sync need every dial).
- **Copy the Recents row exactly** — name + company + flag + local time + disposition badge +
  relative time + call count — and open the **same detail panel** (chevron + double-click).
- **Search (name/company/number)** is P0 — operators redial by name and can't today.
- **Outcome at a glance** (color/icon: connected / no-answer / voicemail / failed) is P0.
- **Disposition badge on the row**, never hidden behind the chevron.
- **Recording Play + notes live in the detail panel, NOT on every row** — "200 dials = 200
  misclicks" (Alex). Unanimous.
- **Dialed-from-number** matters only to multi-number accounts (Marcus) — gate it; clutter for
  solo users.
- **Bulk actions + export** are Marcus's audit job → P1, not a daily-operator need.
- **Traps:** auto-refresh reordering rows under the cursor; the 200-row cap silently breaking
  search/audit; rollup masking a DNC/compliance event; callback as a dateless chip.

Three to ship first: **(1) callbacks-due with real dates pinned on top, (2) rich per-lead rows +
detail panel, (3) search + disposition/outcome filter with outcome color.**

---

## 0.1 Daily-operator validation (`va` agent — second lens)

Ran the plan past the `va` agent (5 SDRs/VAs/managers who live in the app 6–8 hrs/day). They
confirmed callbacks-due is the product but caught that **the buyer lens stopped at create → due;
operators live the whole loop create → due → call → reschedule/done** — and that tail was sitting
in "open questions," not P0. Decisive changes:

- **Promote the full callback lifecycle to P0** (see §2.1). "What clears an overdue / what happens
  after I call them" is *the core loop*, not a risk to resolve later. *"Leave it in open questions
  and I'm back in my notebook by week two."*
- **Capture must be default-and-go, never a modal on every Callback.** Tapping Callback instantly
  commits a sane default (**Tomorrow AM**) and advances the dialer, with an **undo-able chip**
  ("Callback → Tomorrow AM · change", ~5s); the date popover opens only on explicit override.
  Forcing a 5-option popover into the hottest, most-repeated path "= I hate this by day 2" (Alex,
  200 calls/day).
- **In-band snooze / reschedule / mark-done — one click, keyboard-driven.** "Call me next week" is
  15×/day; today that's 4+ clicks in a panel. *The single biggest omission.*
- **Band must be capped + collapsible + timezone-aware + filterable by active number.** A guilt-pile
  of 23 overdue becomes wallpaper and shoves the list off a laptop. Show top 3 + "+N more"; don't
  scream "overdue" when it's 6am at the lead's location; Priya (3 clients) needs the band scoped to
  her active client/number or it's a wrong-script-on-a-live-call risk.
- **Re-tier "Dialed from <my number>" → P0** (gate by *account*, not phase). Most agency SDRs rotate
  numbers for local presence; *"a callback with no which-number is a callback I can't safely
  return"* (Priya). Solo single-number users still don't see it.
- **Rollup badge = most *valuable* state, not latest.** "Interested" yesterday + "No answer" today
  must read **Interested** (outcome color shows the last *attempt*). Latest-wins buries the hottest
  lead. Rollup must also surface the **worst/compliance** flag (DNC).
- **Keyboard operation of the band is P0** (arrow / Enter=call / snooze / done).
- **Missing, P0-worthy: a missed *inbound* (a lead calling back) is louder than any scheduled
  overdue** — "a callback I didn't even choose to owe." Surface it (routing itself is out of scope).
- **Cadence guard (P1):** warn "called 2× today" before a one-click redial from history (Dan).

Operator three-to-ship: **(1) the full callback lifecycle** (default-and-go capture + in-band
snooze/reschedule/done + keyboard), **(2) rich per-lead rows + search by name**, **(3) "dialed
from" on the row**. Everyone would trade the By-Call toggle, date grouping, and export to get the
lifecycle tail into P0.

---

## 1. Current state (what's weak)

`CallHistoryController` + `call-history-view.fxml`: a `TableView<Call>` (≤200 rows, newest first):
`↑Out/↓In · Number(E.164) · Duration · Disposition · Date`, an All/Inbound/Outbound segmented
filter, a Refresh button, double-click → call back, a bottom "Call Back" button.

Gaps: **no lead identity** (raw +E.164 only), no company/flag/local-time, **one row per call**
(same prospect repeats), **no search**, no disposition/outcome filter, **no callbacks-due**, no
recording/notes access, no "which of my numbers," 200-row cap with no paging.

## 1.1 What already exists to reuse (cheap)
- **`CallDisposition.Callback(Instant scheduledAt)`** — the due date is a first-class domain field.
- **Storage already persists it**: `DomainMappers.dispositionToString` writes
  `"callback:<epochMillis>"` and `dispositionFromString` restores it. **No migration needed** to
  read callback due-dates from history.
- **Dialer Recents**: `RecentCallRow` + `RecentCallCell` + `RecentCallFormatter` (relative time,
  call-count label) + `MainWindow.buildRecentRows` (per-number rollup with lead + country + count,
  off-thread). This is the row to generalize.
- **`NumberDetailPanel`** — already shows lead info, disposition chips, notes, per-call timeline
  with **Play recording**, last-contacted / total-calls / talk-time. This is the detail target.
- **`Call`** carries `leadId`, `phoneNumberId` (which owned number), `disposition`, `notes`,
  `recordingPath`, answered/ended/duration — everything the rows + outcome color need.
- **The Callback chip already exists** in `NumberDetailPanel` (and active-call wrap-up) — it just
  hardcodes `Instant.now().plus(1 day)` instead of prompting.

---

## 2. Locked product decisions

- **The callback lifecycle is the product — and it's P0 end-to-end** (create → due → call →
  reschedule/done; full spec in §2.1). Capture is **default-and-go**: tapping **Callback** commits a
  sane default (**Tomorrow AM**) and advances immediately, with an **undo-able chip** to override —
  never a forced popover on the hottest path. A **Callbacks band pinned at the top** (capped +
  collapsible + timezone-aware + filterable by active number) shows **Overdue** then **Due today**,
  each one-click **Call / snooze / done / open-detail**, keyboard-navigable.
- **Per-lead rollup is the default view; per-call is the truth.** Default rows are one per
  prospect (newest activity first) with an **expand to the raw per-dial timeline** (true
  timestamps — Marcus's audit / DNC trace). A **By Lead / By Call** toggle exposes the full
  chronological log (P1). The rollup **never hides** a DNC/failed/compliance event — it surfaces
  "contains DNC." The row **badge shows the most *valuable* state in the stack**
  (Interested/Callback) while the **outcome color reflects the last *attempt*** — latest-wins on the
  badge would bury a hot lead under today's no-answer.
- **Reuse the Recents row + the detail panel.** Generalize `RecentCallCell` → a shared rich
  call-row cell; chevron + double-click open `NumberDetailPanel`. Disposition **badge on the row**.
- **Outcome color is derived, not stored** — classify each call/rollup into
  `CONNECTED / NO_ANSWER / VOICEMAIL / FAILED / DNC` from `status` + `disposition` for a one-glance
  scan.
- **Recording Play + notes are panel-only.** No per-row play button.
- **Search covers all history, not just the loaded page.** Search/filter must query the repo, not
  only the in-memory 200 — the cap is an audit/searchlie trap.
- **"Dialed from <my number>" is P0 for multi-number accounts** (gate by account, not phase):
  agency SDRs rotate numbers for local presence and per-client context, so a callback must show
  which owned number touched the lead. Hidden for single-number solo users.
- **Never reorder rows under the cursor.** Refresh is non-destructive: new data stages and applies
  without yanking the row being read (or via an explicit "N new — refresh" affordance).
- **Single source of truth for callbacks = the call disposition.** `LeadStatus.CALLBACK` may
  mirror it for filtering, but the dated list is derived from dispositions (avoid two-truths).
- **Keep `CallHistoryController` ≤250 lines** → extract the rich cell, the filter/search state, the
  rollup model, and the callback-band into their own classes (headless ones unit-tested).

## 2.1 Callback lifecycle — the core loop (P0, promoted from "open questions")

| Phase | Behavior |
|---|---|
| **Create** | Tap **Callback** → instantly commit `Callback(scheduledAt = Tomorrow-AM default)` and advance the dialer; show an **undo chip** ("Callback → Tomorrow AM · change", ~5s). The date popover (Later today / Tomorrow AM / +2d / Next week / Pick…) opens **only** when the rep clicks **change**. |
| **Due** | Appears in the band: **Overdue** (red) then **Due today** (amber). Band is **capped** (top 3 + "+N more"), **collapsible** (state remembered), **timezone-aware** (a lead it's 6am for is not screamed as callable), and **filterable by active number/client**. |
| **Call** | Dialing a callback **at/after its due time and connecting** auto-marks it **honored** → it clears from the band. |
| **No-answer** | A callback dialed but **not reached stays**, and **re-prompts "reschedule?"** — it never silently slides back to overdue. |
| **Reschedule / snooze / done** | **One click in-band**, keyboard-driven: **+1 day**, **Next week**, **Done** (resolve, no new callback). No panel round-trip. |

"Honored" = an outbound **connected** call after `scheduledAt` for that number; that is what clears
overdue. This loop — not just the dated chip — is what makes the band trustworthy enough that an
operator abandons their notebook.

---

## 3. Architecture & components (bottom-up)

### 3.1 Domain (`domain/`) — already sufficient
- `CallDisposition.Callback(Instant scheduledAt)` — no change.
- (Optional, P0) a small derived **`CallOutcome`** enum lives in `ui/support` (it's a view concept,
  not domain truth), classified from `status` + `disposition`.

### 3.2 Storage (`storage/`) — additive query methods (no migration)
- **`CallRepository.findCallbacks()`** — calls whose `disposition LIKE 'callback:%'`, newest per
  remote number, returning the parsed `scheduledAt`. (SQL `LIKE` prefilter; parse the
  `callback:<ms>` in the repo via `DomainMappers`. Order by scheduledAt.)
- **`CallRepository.searchHistory(query, direction?, dispositionKinds?, limit, cursor?)`** —
  name/number search + filters **over all history** (keyset paginated), so search never lies at the
  200 cap. (Name/company search joins leads by phone, or the service resolves leads after a number
  match — see §3.3.) *Carve-out:* if a full keyset search is too big for P0, ship a
  `findRecent(limit)` bump + repo-side `LIKE` number search first, name/company filtered in the
  service, and land true keyset search in P1.
- Reuse existing `findByRemoteNumber` (per-number timeline + count) and `findRecent`.

### 3.3 Services (`services/`)
- **`CallService.callbacksDue()`** → `List<CallbackEntry>` (number, leadId, scheduledAt, last
  outcome, `honored`), sorted by due time; the UI buckets them Overdue/Today/Upcoming and drops
  honored ones. **Honored** = an outbound *connected* call to that number after `scheduledAt`.
- **`reschedule(number, Instant newWhen)`** updates the latest callback's `scheduledAt`;
  **`resolveCallback(number)`** marks it done (closed, no new callback). Both update the underlying
  call's disposition so the band reflects it immediately — **no new dial required**.
- **`missedInbound()`** → inbound calls with no answer (a lead calling back) for the
  "louder-than-overdue" band entry. (Surfacing only; inbound *routing* is out of scope.)
- **`callsToday(number)`** → count for the P1 cadence guard ("called 2× today").
- **`history(filter)`** → page of calls (delegates to `searchHistory`); the UI builds rollups.
  Lead + country resolution stays where `buildRecentRows` does it (off-thread).
- Keep DNC enforcement + the existing dial/wrap-up paths unchanged.

### 3.4 UI (`ui/`)
- **`CallHistoryController`** rebuilt around: a **search field**, **filter chips** (direction +
  disposition/outcome presets: Callbacks · No-answer · Interested · Voicemail · DNC), a pinned
  **Callbacks band**, and a **`ListView` of rich rows** (replacing the `TableView`). Off-thread
  load → `Platform.runLater`. Non-destructive refresh.
- **`CallRowCell`** (NEW, generalized from `RecentCallCell`): two-line lead-aware row + disposition
  **badge** + outcome color stripe/icon + relative time + call count + a **›** chevron; reused by
  Recents *and* Call History. (Refactor `RecentCallCell` to delegate to it, or extract a shared
  base — DRY the cell, keep the two call sites.)
- **`CallbackBand`** (NEW view): the pinned Overdue/Due-today section; each entry → call / open
  detail; shows the promised time ("Thu 3:00 PM · overdue 2d").
- **Disposition capture with a date** — extend the existing Callback chip (in `NumberDetailPanel`
  and the active-call wrap-up) to open a tiny **when?** popover (presets + pick) instead of
  hardcoding +1 day. (Shared helper so both capture points agree.)
- **Detail target** = existing `NumberDetailPanel` (recording Play + notes live here).

### 3.5 Headless support (NEW, `ui/support/` — **unit-tested**)
- **`CallOutcome`** — `classify(status, disposition)` → CONNECTED / NO_ANSWER / VOICEMAIL / FAILED /
  DNC (+ its color/label). Pure, table-tested.
- **`CallHistoryRow`** — the per-lead rollup VM: number, `Optional<Lead>`, `Optional<Country>`,
  lastCallAt, callCount, **`badgeDisposition`** (the most *valuable* state in the stack),
  **`CallOutcome`** (of the last *attempt*), `Optional<Instant> callbackDueAt`, `containsDnc`,
  `Optional<String> dialedFromLabel`. Built off-thread (mirrors `RecentCallRow`).
- **`CallbackBuckets`** — given the callbacks + "now" + the lead's timezone, split into
  **Overdue / Due today / Upcoming**, **drop honored**, **de-emphasise not-callable-yet** (lead's
  local night), and cap + total for the "+N more" pill. Pure, tested — the core never-miss logic.
- **`CallHistoryFilterState`** — search text + direction + disposition/outcome presets → a filter
  the service/repo understands; mirrors `LeadFilterState`.
- **`CallbackWhen`** — maps a preset (Later today / Tomorrow AM / +2d / Next week) + "now" → an
  `Instant`. Pure, tested (so "Tomorrow AM" is deterministic).

---

## 4. UX / visual spec

### 4.1 Header
`Call History` · **search** (name / company / number) · filter chips:
`[All][Inbound][Outbound]` and outcome presets `[Callbacks][No answer][Interested][Voicemail][DNC]`
· **By Lead / By Call** toggle (P1) · non-destructive **Refresh**.

### 4.2 Callbacks band (P0 — the headline + the loop)
```
⏰ Callbacks (3)                                                       [collapse]
  ● Overdue   Jane Doe · Acme   promised Thu 3:00 PM (2d ago)  [Call] [+1d] [Next wk] [Done] ›
  ● Due today +1 415… (no lead)  promised Today 5:00 PM         [Call] [+1d] [Next wk] [Done] ›
  … +20 more
```
- Shows only when callbacks are due/overdue; **Overdue** first (red), **Due today** (amber).
- **Capped** to the top 3 with a **"+N more"** pill; **collapsible** (state remembered) so it never
  shoves the list off a laptop screen.
- **Timezone-aware:** a callback the lead can't be called yet (their local night) is de-emphasised,
  not screamed as overdue.
- **Scoped to the active number/client** when one is pinned (Priya's three-client day).
- **In-band, keyboard-first actions:** ↑/↓ move · Enter = Call · S = snooze (+1d) · N = next week ·
  D = done. No panel round-trip for the 15×/day reschedule.
- A **missed inbound** (a lead called back, unanswered) appears **above** scheduled overdue — a
  promise you didn't choose to owe, and often the hottest lead of the day.

### 4.3 Rich rows (P0 — "what happened last time")
Reuse the Recents cell:
```
Jane Doe   +1 415 555-1234     24 min ago                4 calls   [Interested]
🇺🇸 USA     2:14 PM · PST                         [Call] [Message]        ›
```
- **Disposition badge** on the row; a leading **outcome color** dot/stripe (connected/no-answer/…).
- Per-lead rollup; chevron / double-click → `NumberDetailPanel` (timeline + recordings + notes).
- "Dialed from +1 916…" appears **only** for multi-number accounts.

### 4.4 Disposition capture — default-and-go (P0 — the fast path stays fast)
Tapping **Callback** **immediately** commits `Callback(scheduledAt = Tomorrow AM)` and lets the
dialer advance — no modal in the hot path. A transient **undo/override chip** ("Callback →
Tomorrow AM · change") appears for ~5s; clicking **change** opens the `when?` popover
(`Later today · Tomorrow AM · In 2 days · Next week · Pick…`). Same behavior wherever a disposition
is set (active-call wrap-up + detail panel). Persisted as `Callback(scheduledAt)` (already wired).

### 4.5 By-Call log (P1)
The toggle reveals the raw chronological per-call list (audit truth) — every dial, timestamped,
exportable; **date grouping (Today / Yesterday / This week)** applies *here* (P2), not in the
rollup.

---

## 5. Threading
- All loads (`callbacksDue`, `history`, rollup building, lead/country resolution) run off the FX
  thread via `CompletableFuture` → `Platform.runLater`. Relative-time labels recompute on a gentle
  periodic `ListView.refresh()` — but **never reorder** the list under the cursor.

---

## 6. Phasing (each slice green via `./gradlew test`)

- **P0 — the full callback loop + lead-aware rows + search:**
  1. **Callback lifecycle (§2.1):** default-and-go capture (`CallbackWhen` + undo/override chip),
     `CallRepository.findCallbacks` + `CallService.callbacksDue/reschedule/resolveCallback`,
     `CallbackBuckets` (tested, timezone-aware, honored-drop), the **band** (capped, collapsible,
     keyboard-first, in-band snooze/next-week/done), and **honored auto-clear** on a connected
     post-due call.
  2. **Rich per-lead rows + detail:** `CallOutcome` + `CallHistoryRow` (tested, most-valuable
     badge), `CallRowCell` generalized from Recents, list replaces the table,
     chevron/double-click → `NumberDetailPanel`, expand → **raw per-dial timeline**.
  3. **Search + disposition/outcome filter:** `CallHistoryFilterState` (tested) + repo-backed
     number search + service-side name/company filter + outcome color.
  4. **"Dialed from <my number>" on rows** for multi-number accounts (gated by account).
  5. **Missed-inbound** surfaced above scheduled overdue in the band.
- **P1 — operator + audit depth:**
  6. **By Lead / By Call toggle** (full chronological audit log).
  7. **Bulk actions** (multi-select → add to list / mark DNC / **export CSV**) — Marcus's audit.
  8. **Cadence guard** ("called N× today" warning before a one-click redial).
  9. **True keyset search/paging over all history** (retire the 200 cap for search).
- **P2 — polish:**
  10. **Date grouping** (Today/Yesterday/This week) in the **By Call** view only.

---

## 7. Testing
- **Tested (headless):** `CallOutcomeTest` (status×disposition → outcome table),
  `CallbackBucketsTest` (overdue / due-today / upcoming split across day boundaries, **honored
  dropped**, **not-callable-yet de-emphasised by lead timezone**, cap + "+N more" total; injected
  clock), `CallbackWhenTest` (each preset deterministic vs a fixed "now"),
  `CallHistoryRowTest` (rollup: count, **most-valuable badge beats latest**, `CallOutcome` = last
  attempt, `containsDnc`, `callbackDueAt`), `CallHistoryFilterStateTest` (presets → predicate).
  Service: `callbacksDue` **honored detection** + `reschedule` / `resolveCallback` transitions.
  Repo: `findCallbacks` / `searchHistory` against in-memory SQLite (parse `callback:<ms>`, paging,
  filters).
- **Not tested:** `CallRowCell`, `CallbackBand`, the rebuilt controller (JavaFX views).
- Coverage per `AGENTS.md` (services ≥90%, storage ≥85%, UI support is the tested surface).

---

## 8. Deliberately deferred / NOT building (YAGNI — per buyer)
- **Recording Play / notes on every row** — panel-only (unanimous misclick trap).
- **Date grouping in the rollup** — conflicts with per-lead spanning dates; By-Call view only, P2.
- **Bulk export / CRM sync semantics** beyond CSV — P1+, and a separate integration concern.
- **A standalone "Callbacks" screen** — surface in Call History first; promote later only if it
  outgrows the band.
- **Vanity metrics** (raw "total calls" as a headline) — Lisa: the funnel that matters is
  connected → callback → callback-honored, not a tally.

---

## 9. Risks / open questions
- **Parsing `callback:<ms>` in SQL.** `LIKE 'callback:%'` prefilters cheaply, but the timestamp is
  inside the string — parse in the repo via `DomainMappers`, don't try to sort by it in SQL. Confirm
  there's no perf cliff at audit volume (Marcus); if so, P1 adds a dedicated `callback_due_at` column
  (migration) as a denormalization.
- **Reschedule / resolve storage.** Callbacks are derived from call dispositions, so **reschedule**
  updates the latest callback call's `scheduledAt` and **resolve/done** writes a terminal marker on
  it (no new dial). Confirm updating a historical call's disposition is acceptable vs. appending a
  lightweight `callback_events` row (a small migration) — prefer the append model if audit needs the
  original promise preserved.
- **Missed-inbound scope.** The band *surfaces* missed inbound calls; actual inbound **routing /
  notification** is a separate surface (see the sidebar plan's notification gap) — this plan does not
  build routing.
- **Two-truths with `LeadStatus.CALLBACK`.** Keep the dated list derived from dispositions; if the
  lead-status mirror drifts, dispositions win. Don't show two callback lists.
- **Search over all history vs the 200 cap.** P0 must at least make search hit the repo for numbers;
  full name/company keyset search may slip to P1 — flag clearly so we don't ship a search that
  silently only covers the loaded page.
- **Non-destructive refresh.** Needs a concrete interaction (stage + "N new" pill vs. silent append)
  so a high-volume operator is never reordered mid-read.
