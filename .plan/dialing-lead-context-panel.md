# Plan — Auto-Open Lead Context Panel on Dial

> Status: **Implemented (P0)** — outbound auto-open + read-only live-call mode + Tier 1/2/3 progressive-disclosure lead card shipped. Inbound auto-open (P1) still deferred.
> Goal: the moment a call starts dialing, automatically surface everything known about that lead — full lead record (all columns) + call history — in the existing right-docked panel, without breaking the live-call loop.
> Reviewed by: `buyer` agent (6-persona ICP panel). Verdict folded in below (convergence was unanimous on the one decision that matters).

## Implementation (P0) — what shipped

| Concern | Resolution |
|---|---|
| Tiering logic (headless, TDD) | `ui/support/LeadFieldDigest` — Tier 1 name + company·title; Tier 2 DNC/status/tags; Tier 3 email + custom columns (sorted, case-insensitive) + notes + added date. 11 unit tests. |
| Progressive-disclosure view | `ui/LeadGlanceCard` — Tier 1/2 always visible; Tier 3 behind one "Show all fields ▸" expander (collapsed by default). Replaces old `leadCard()`. |
| Read-only live-call mode | `NumberDetailPanel.showForCall()` — hides action bar, disposition chips, note field, edit form; shows "On this call" hint + previous outcome. Center call screen remains the sole disposition/note writer (the one decision). |
| Keyboard collision (§9.1) | In live-call mode the panel sets `focusTraversable=false`, never `requestFocus()`, and `onKey()` no-ops so Esc/M/V/K bubble to the call screen. |
| Controls clipping @ 960px (§9.2) | `MainWindow.LIVE_CALL_MIN_WINDOW_W = 1100` clamps min width while docked beside a live call; restored to 960 on close. |
| Auto-open on dial | `MainWindow.showCallStarting()` → `openNumberDetailForCall()`; `returnFromCall()` → `closeNumberDetail()`. Outbound only; `openNumberDetail(String)` history signature unchanged. |

---

## 1. The request (verbatim)

> "Whenever I am dialing the call I need to see all the information from the lead list of that customer — a new pop up or sidebar should open automatically with call history and all the lead data with all the columns."

## 2. What already exists (verified in code)

We do **not** start from zero. A non-blocking, right-docked side panel already does ~80% of this:

| Component | File | What it does today |
|---|---|---|
| `NumberDetailPanel` (380px) | [src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/NumberDetailPanel.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/NumberDetailPanel.java) | Header (name/number, flag, local time), quick actions (Call/Message/Edit/Copy), **disposition chips + auto-saving note** (edits latest call), stats strip, **Lead card**, merged **call+SMS timeline** w/ recording playback. Loads off the FX thread. |
| Dock + open/close | [MainWindow.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/MainWindow.java#L542-L558) (`openNumberDetail`, `closeNumberDetail`) | `root.setRight(panel.getRoot())` / `root.setRight(null)`. |
| Triggers today | [MainWindow.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/MainWindow.java#L583) + [#L624](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/MainWindow.java#L624) | Only on **Call History** row click and **Dialer → Recent Calls** row click. |

**The two real gaps vs. the request:**

1. **It never opens on dial.** It opens only when you *click a row before* you call. In the Power Dialer (auto-advance) the rep never gets that click — they're half-blind on the connect.
2. **The Lead card omits most columns.** [`leadCard()`](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/NumberDetailPanel.java#L454) shows only name, company·title, email, tags, notes, DNC. It does **not** show the lead's **custom fields** (the dynamic per-import columns — `Lead.customFields()` is a populated `Map<String,String>`), **lead status**, or created/updated. The Leads table screen *does* show all of these.

**Layout fact (so a right panel can coexist with a live call):** the active-call screen is a **centre** swap (`root.setCenter(activeCallView)` — [MainWindow.java#L286](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/MainWindow.java#L286)). Sidebar = 190px, panel = 380px, min window = 960px, default = 1280px. So centre (~390px @ min, ~710px @ default) + right panel physically fit. **All outbound dials funnel through one hook:** [`callService.setOnCallStarting → mainWindow.showCallStarting`](src/app/src/main/java/com/elitale/coldbirds/coldcalling/app/ColdCallingApp.java#L318-L321).

---

## 3. The ONE decision that matters (buyer panel, 6/6)

**Kill one of the two disposition+note surfaces during a live call. Do not style both to coexist.**

The active-call screen already owns disposition buttons + a "saved automatically" note box. The panel *also* has disposition chips + a note field. Shipping both on screen at once is the unanimous trust-breaker:

- *"Two 'add note' boxes and two sets of disposition buttons? I'll type in one, hit disposition in the other, and now I don't trust either saved. That's the moment I screenshot it to the team Slack as broken."* — Alex (SDR, 200 calls/day)
- *"Which surface is the source of truth? My dashboards depend on clean disposition data. Pick one writer."* — Marcus (agency, 2,400 calls/day)
- *"If disposition can be set in two places, my RevOps person asks 'which one writes to Salesforce?' — if the answer is 'both', that's a failed data review."* — Jake (manager, committee buyer)

**Resolution — state-based ownership:**
- **During connecting / active / wrap-up:** the **centre active-call screen** is the *sole* writer of disposition + note. The panel's disposition chips + note field are **hidden** and replaced by a one-line read-only hint (e.g. *"Disposition & notes on the call screen →"*). Panel = pure read context.
- **When opened from History / Recent Calls** (the panel's *original* job, no live call): disposition + note stay **editable, exactly as today.**

One writer, decided by call state. No "which box do I type in?", no dirty analytics.

---

## 4. Second decision: "all columns" ≠ "everything on screen at once"

The request ("all the data with all the columns") is the user describing the *symptom* ("I can't see enough"), not prescribing a wall of 20 CSV columns mid-call. Buyer consensus: reps want a **3-second glance**, with everything else **one scroll/expander away.**

**Information hierarchy for the Lead card:**

| Tier | Always? | Fields |
|---|---|---|
| **1 — large glance** | Always | Name · Company · Title · number being called |
| **2 — secondary** | Always | **Last disposition + when** ("Callback · 2 days ago"), total calls, **DNC (loud if set)**, tags |
| **3 — collapsed behind "Show all fields ▸"** | On expand | **every custom field** (label:value), lead status, email, notes, created/updated |

This **contains** all fields (satisfies the literal ask) but **progressively discloses** them. "Full scroll access = yes. Full simultaneous display = no."

---

## 5. Scope — P0 / P1 / P2 / Cut

### P0 — smallest version that earns trust + renewal
1. **Auto-open the panel on dial** — in `showCallStarting` (covers manual dial, redial, **and** power-dialer auto-advance since they all funnel through it). Non-blocking, docked right. **Close it wherever the call screen is dismissed** (`returnFromCall`, and the failed-call close path).
2. **Resolve the dual-surface (the trust-maker):** add a **live-call mode** to `NumberDetailPanel` that *hides* its disposition zone + note zone and shows the read-only hint. Centre screen stays sole writer. **Without this, P0 ships a perceived bug.**
3. **Live-call mode also surrenders the keyboard** (see §9 — the panel's `Esc/M/V/K/C` hotkeys collide head-on with the call screen's `Esc`=hang-up, `M`=mute, `V`=voicemail, `K`=keypad). In live-call mode the panel must not be focus-traversable and must not install its `KEY_PRESSED` filter.
4. **Lead card Tier 1 + Tier 2 glance** (name/company/title large; last disposition+date, total calls, DNC loud, tags).

### P1 — strong follow-up
5. **"Show all fields ▸" expander** → all custom columns + status + email + notes + created/updated. *This is the literal "all columns" ask, done right.*
6. **Dedup / no re-animate:** if the same number's panel is already open (manual dial right after viewing the row), don't re-trigger the slide-in.
7. **Unknown-number state:** number with no matching lead → explicit *"No lead found · [Add Lead]"* (panel already renders a "No lead saved" + Add path — make it read intentional, not empty/broken).
8. **Inbound answered calls** also auto-open the panel (`showActiveCall` inbound branch). Same read-only live-call mode.

### P2 — nice, not urgent
9. **Admin-pinned custom fields per call list** — let an admin promote 1–3 custom fields (e.g. "Industry", "Deal Size") into Tier 2. Asked for *unprompted* by Marcus **and** Jake — the one piece here that's a mild differentiator rather than table stakes. Needs settings UI + per-list config.
10. **Disposition-origin logging** for analytics auditability (Jake/Lisa's data-integrity ask).

### Cut — theater / churn risks
- ❌ **Two live, always-editable disposition+note surfaces.** "Looks like 'we added power', reads like 'we added a bug'." This is *the* churn moment.
- ❌ **All custom columns expanded by default mid-call.** Visual noise that makes the 3-second glance *slower*.
- ❌ **Suppressing the pop entirely on manual dial.** Inconsistent; also kills the useful "no pop = unknown number" signal. Pop everywhere; just don't re-animate if already open.

---

## 6. Codebase mapping (reuse vs. build)

| Need | Reuse | Build |
|---|---|---|
| Auto-open on dial | `openNumberDetail(number)` already docks the panel | Call it from `showCallStarting` (P0) + inbound `showActiveCall` (P1); pass a `liveCall` flag |
| Close on call end | `closeNumberDetail()` already exists | Call it from `returnFromCall` + failed-call dismiss |
| Read-only live-call mode | `NumberDetailPanel.show()` render pipeline | `setLiveCallMode(boolean)` (or `showForCall(number)`): skip `dispositionZone()`/`noteZone()`, render hint, drop key filter + focus traversal |
| Tier 1/2/3 lead card | `leadCard()` as the starting point | Extract to a small `LeadGlanceCard` helper (progressive disclosure; keeps `NumberDetailPanel` under control — it's already ~640 lines) |
| All custom fields | `Lead.customFields()` (`Map<String,String>`), `leadStatus()`, `createdAt()` | Render label:value rows inside the Tier-3 expander |
| Last disposition + when | `latestCall` + `CallDisposition` + `RecentCallFormatter.timeAgo` (all present) | Tier-2 line |
| Layout safety @ 960px | — | Verify hang-up/mute never clipped behind the 380px panel (see §9) |

---

## 7. Architecture / AGENTS.md compliance

- **UI-only change for P0** (reuses existing services; no new repo/migration/domain types). DB reads already run off the FX thread inside `NumberDetailPanel.show()` → `Platform.runLater`.
- **SRP / file size:** `NumberDetailPanel` is already large. Extract the enhanced lead card into its own `LeadGlanceCard` (P0) so the panel doesn't grow past readability and the Tier-1/2/3 logic is unit-testable headless.
- **No second writer:** disposition/note persistence stays single-source (centre call screen during a call; panel only when no call). Protects the call-analytics data that managers/VPs sync.
- **Tests:** `LeadGlanceCard` field-tiering logic (which fields land in Tier 1/2/3; custom-field iteration; DNC emphasis; empty-lead + unknown-number states) is headless → TDD. Live-call-mode toggle asserted (disposition/note nodes absent; key filter not installed).
- **Keep controllers thin:** this lives in the UI component + `MainWindow` wiring only.

---

## 8. Open questions (please confirm before build)

1. **Close timing.** Confirm the panel should **close when the call screen is dismissed** (return to origin), i.e. it's live-call context only — *not* left open afterward. (Buyer-aligned recommendation: close on dismiss; the editable-from-history path is unchanged.)
2. **Inbound calls** — open the same panel on an answered inbound call (P1), or strictly outbound dialing as worded? Recommend P1 inbound for consistency.
3. **Min-width handling** (see §9): acceptable to **raise the stage min width to ~1120px while a live-call panel is docked** (restore on close) so the call controls never clip? Or prefer a **collapsible panel** (chevron) the rep can hide? Recommend the dynamic min-width clamp — simpler, zero new affordance.
4. **P2 admin-pinned fields** — in scope at all, or defer until per-call-list settings exist? (It's the only differentiator vs. table-stakes; everything else just matches JustCall/Salesloft.)

---

## 9. Edge cases & traps (found in code review + buyer panel)

1. **🔴 Keyboard collision (must fix in P0).** Panel `onKey` maps `Esc`=close, `M`=message, `V`/`K`/`C`/`I/X/A/B/D` = dispositions/actions. The active-call screen maps `Esc`=**hang up**, `M`=**mute**, `V`=**voicemail drop**, `K`=**keypad**. If the panel keeps focus + its `KEY_PRESSED` filter during a call, pressing `Esc` to hang up would instead just close the panel, and `M` would message instead of mute. **Live-call mode must drop the filter and `setFocusTraversable(false)`** so the call screen owns all hotkeys.
2. **🟡 Controls clipped @ 960px.** Centre shrinks to ~390px with the panel docked. Verify the 8 round call-control buttons + hang-up never wrap off-screen or hide behind the panel — *"if hang-up is ever hidden, that's an instant rage-uninstall."* Mitigation per §8 Q3.
3. **Non-E.164 / unknown caller-ID.** `PhoneNumber`'s constructor throws on anything not `\+[1-9]\d{1,14}`. The panel already guards this (`renderInvalid`) — ensure the live-call open path reuses that guard so a private/short-code inbound number doesn't crash the pop.
4. **Re-animation flash on manual dial.** Dialing a lead you *just* viewed re-slides the panel. Dedup by current number (P1).
5. **Empty pop on unknown number looks broken.** Needs the explicit "No lead found · [Add Lead]" state (P1), not a blank panel.
6. **Disposition shown may be an auto-guess.** `CallService.persistCallRecord` defaults a normal hang-up to `NotInterested`. The Tier-2 "last disposition" is context, not gospel — fine to show, don't over-trust it (consistent with the recent-call-detail plan).

---

## 10. Suggested phasing

- **Phase A (P0):** `showCallStarting` auto-opens panel in live-call mode; `returnFromCall`/failed-dismiss closes it; live-call mode hides disposition+note, drops keyboard/focus; `LeadGlanceCard` Tier 1+2. Verify §9.1 + §9.2. (UI-only; TDD on `LeadGlanceCard`.)
- **Phase B (P1):** "Show all fields ▸" expander (all custom columns + status + dates); dedup-no-reanimate; unknown-number state; inbound auto-open.
- **Phase C (P2):** admin-pinned per-list fields (needs settings); disposition-origin logging.

Each phase ends green on `./gradlew build` + `./gradlew test`.

---

## 11. Buyer convergence verdict (condensed)

> **Build it — P0 as scoped — but the proposal as literally written (two live disposition+note surfaces + all columns expanded) would ship a feature reps read as broken and committee buyers reject on data-integrity grounds.** Auto-open: yes, all contexts, power-dialer-first. All fields: contained but progressively disclosed, never all-expanded mid-call. The make-or-break is singular: **one disposition+note writer, chosen by call state.** Ship that and 6/6 go from "toy" to "real dialer."

- NEED the pop: **6/6** · Would pay *more*: **0/6** (table stakes, not an upsell) · Churn-prevention potential: **High** (its *absence* feels toy-grade; the *dual-write* version manufactures churn).
