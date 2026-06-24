# New Conversation (the "New" button) — find the person, not the digits

> One line, taped to the monitor (the buyer floor's words): **find the person (or number), confirm
> who + from which of my numbers + that they're textable, then drop into the normal thread.** Not
> "type a number, type a text, send."
>
> Scope: `ui/` (Messages) + small `services/` reads. Expands item **J** of
> [`messages-redesign.md`](./messages-redesign.md) and pulls its From-default / unknown-number /
> DNC-at-pick threads into one focused flow. **Plan only — do not implement.**

---

## 0. Buyer-agent validation (verdict that shaped this plan)

Stress-tested the current dialog with the `buyer` agent (6 cold-calling buyers/operators). Headline:

> "This dialog is a **developer's mental model of SMS, not a cold caller's.** It assumes the rep has a
> clean E.164 in their head, that the 'From' number was correctly chosen on a screen they've since
> stopped looking at, and that the contact is safe to text — all three false at hour 6. It's not a v1
> of a feature; it's **a debug console that shipped.** The good news: you already own every piece
> needed to build the right thing (lead search, DNC flag, multi-number ownership, a real compose bar)
> — this flow just doesn't *use* any of them."

Failure modes they named within "day one": Priya fat-fingers a number and texts a stranger; Alex
texts a DNC lead (only blocked at send, looks like luck); Marcus's SDR sends from the **wrong
client's number** and burns number reputation + thread continuity; Carlos churns because typing `+1`
from memory in 2026 "feels broken."

---

## 0.1 Jobs-to-be-done (ranked) — the dialog optimizes the rarest one

1. **"Text a lead I haven't texted before"** — ~70%+ of clicks. The person *is* in the Leads DB
   (called/imported/in a list), just not in the recent thread list yet. **A lookup problem, not a
   typing problem.** The current dialog is actively hostile to it.
2. **"Re-open someone who fell off my recent threads"** — search-and-resume; today the rep retypes
   the number and never sees the existing history.
3. **"Text a number from a sticky note"** (cold paste) — the *only* job raw-E.164 entry serves, and
   even here the right move is paste → recognize → offer to create a lead, not paste → blind send.
4. **"Quick blast to a few people"** — real but **out of scope** (a campaign surface, not this button).

---

## 1. Current state

`MessagesController.showNewMessageDialog()` opens a generic modal `Dialog`:
- **"To:"** = a raw **E.164** text field (`+15550001234`); strips spaces/dashes/parens, strict-parses
  `PhoneNumber`, error alert on anything else. No lead search, no identity, no DNC.
- **"Message:"** text field — a first message is **required** to proceed (a cramped, worse compose box
  than the real bar).
- **OK** sends immediately via whatever **From** was selected back on the Messages screen (invisible
  here), then opens the thread. **CANCEL/OK** modal.
- It is a **second, divergent entry point** from `openConversation(remote)` (the "Message from call"
  path), with its own blind-send logic.

What already exists to reuse (so v1 is wiring, not new infra):
- `LeadService.search(query)` (full-text name/company/phone), `findByPhone`, `isDnc`.
- `PhoneNumberService.listOwned()`, `getPinnedOutbound()` (pinned default), `OwnedNumber.areaCode()`.
- `SmsService.findThread(remote)` (existing history), `send` (already DNC-guards + persists Failed).
- `MessagesController.openConversation(remote)` — already selects an existing conversation if present,
  loads its history, and focuses the compose bar (the real one, with segment counter + opt-out guard).
- `PhoneNormalizer` (services) — normalize typed input toward E.164.

---

## 2. Locked product decisions

- **Contact picker first; raw number as fallback.** The "To" field is a **live autocomplete over leads**
  (name / company / number). Each row shows **name · company · number · DNC badge** so the rep
  disambiguates instantly (kills "wrong Dave"). If what they type parses as a number and matches no
  lead, offer a **"Text {E.164}"** raw row.
- **Kill the modal — go inline.** Replace the popup with an **inline "To:" composer** at the top of the
  thread pane (iMessage / WhatsApp / JustCall muscle memory). Picking a suggestion transitions straight
  into the resolved thread. (1:1 only, so no persistent multi-token chip field needed in v1.)
- **Open the thread; never compose in the dialog.** Resolve *who* (+ *from which number*) in the entry
  step, then drop into the **normal compose bar**. "New" must not be a worse compose experience than
  "reply." A rep who only wants to *pull up* a contact (job #2) must not be forced to fake a message.
- **From-number is explicit and lead-aware in the flow.** Show the From selector; default it by
  priority: **(1) the number this conversation already uses** (SMS thread continuity) → **(2) pinned /
  default outbound** → **(3) first active**. (Call-continuity + local-area-match = P1, reusing
  `CallerIdSelector`.) Never inherit a hidden From from another screen.
- **DNC/opt-out is gated at *pick* time, visibly** — red badge on the suggestion row, opt-out banner +
  disabled compose in the opened thread (already built). The send-time block stays as the floor, but
  the rep must see it *before* composing. (Legal floor — Lisa/Jake veto otherwise.)
- **Unify entry points.** `onNewMessage`'s pick funnels through the **same** `resolveAndOpen(remote)`
  as the call→Message path. One resolver, one behavior, **no duplicate-thread forking** (threads are
  keyed by remote number; routing through `openConversation` always surfaces existing history first).
- **Unknown number → offer to create/link a lead** (lightweight, **skippable**, P1) so the activity
  isn't orphaned (bare-number thread header, split history, broken roll-up reporting).
- **Bulk / multi-recipient is out of scope** — a separate campaign surface with throttling, rotation,
  opt-out footer, per-number caps. Conflating it here is how a rep nukes a number's reputation.

---

## 3. Architecture & components (bottom-up)

### 3.1 Services (`services/`)
- **`SmsService.threadNumber(PhoneNumber remote) → Optional<PhoneNumberId>`** — the owned number the
  most recent message of this conversation is on (in or out). Drives From continuity. Tested.
- *(Already there)* `findThread`, `send`, `LeadService.search/findByPhone/isDnc`.

### 3.2 Headless support (`ui/support/` — **unit-tested**)
- **`ContactSuggestion`** — picker row view-model: `Optional<Lead> lead`, `PhoneNumber number`,
  `boolean dnc`; `displayName()` (lead name else formatted number), `subtitle()` (company · number),
  `isRawNumber()`. Built from `leadService.search` (LeadMatch) + a RawNumber row when the query
  parses. Pure, tested (display, subtitle, dnc, raw vs lead).
- **`FromNumberDefault`** — `resolve(List<OwnedNumber> owned, Optional<PhoneNumberId> continuity,
  Optional<PhoneNumberId> pinned) → Optional<OwnedNumber>`: continuity-in-owned → pinned-in-owned →
  first-active → empty. Pure, table-tested. **The anti-"wrong-From" unit.**
- *(P1)* extend `FromNumberDefault` with call-continuity + local-area-code match (reuse
  `CallerIdSelector`'s last-reached / local-presence logic rather than re-deriving it).

### 3.3 UI (`ui/`)
- **New-message inline state** in `messages-view.fxml` (toggled by visibility, not a modal): a **"To:"
  `TextField`** + a **suggestions `ListView<ContactSuggestion>`** that replaces the thread header while
  active; Esc / a back affordance cancels.
- **`ContactSuggestionCell`** — avatar monogram + name + company + number + **DNC badge** (mirrors
  `ConversationListCell` styling; view-only).
- **`MessagesController`**:
  - `onNewMessage()` → enter the inline To-state (focus the field, clear suggestions).
  - debounced `searchContacts(query)` off-thread → `leadService.search` + raw-number parse →
    `List<ContactSuggestion>` → render.
  - `pick(ContactSuggestion)` → `resolveAndOpen(number)`.
  - **`resolveAndOpen(PhoneNumber)`** (the unified seam): `openConversation(number)` (selects existing
    or opens fresh + focuses compose) **and** set `fromNumberCombo` via `FromNumberDefault.resolve(...)`;
    the opened thread already shows the opt-out banner + disables compose for DNC. The call→Message path
    is repointed to this same method.
  - Keep the controller ≤250 lines — extract the picker wiring into a small helper if it pushes over.
- **Default-From on every thread open** (not just New): set `fromNumberCombo` from
  `FromNumberDefault` inside `loadThread`, so the From-continuity fix applies app-wide (also satisfies
  the redesign's P1 "From defaults to thread-continuity").

---

## 4. UX / flow spec

### 4.1 Entry (click "New")
- The thread pane swaps its header for a **"To:"** field (placeholder *"Name, company, or number"*),
  cursor focused. The compose bar is hidden until a target is picked. A subtle **back/✕** returns to the
  previous thread (or the empty state).

### 4.2 Typing → suggestions
```
To: dav|
┌───────────────────────────────────────────────┐
│ (DP)  Dave Park · Acme Corp     +1 512 555 0142 │
│ (DM)  Dave Mehta · Initech      +1 415 555 0198  ⛔ Opted out │
│  #    Text +1 512 555 0142  (no lead)            │   ← only if it parses
└───────────────────────────────────────────────┘
```
- Each lead row: avatar · **name** · company · number, plus a **red "Opted out" badge** when DNC.
- Empty field (P1): show a **recent-leads / recently-called shortlist** so most picks are zero-typing.

### 4.3 Pick → resolved thread
- Transition into the normal thread for that person: header shows **name · company · flag · local
  time** (already built); **From** is set to the lead-aware default and is visible/changeable; the
  **real compose bar** (segment counter, opt-out guard) takes focus.
- If a **thread already exists**, its history is right there (no fork). If new, an empty thread.
- If **DNC/opted-out**: the **"Opted out — texting disabled"** banner shows and compose is disabled —
  the rep never wastes time writing an unsendable text.

### 4.4 Unknown number (P1)
- Raw row picked / number with no lead → a one-line inline **"No lead found — [Create lead]"** (name +
  company, optional, skippable). On create: link the thread, set the lead's context, default its number
  to the From just used. Never blocks sending.

---

## 5. Threading
- `leadService.search` and `smsService.threadNumber` run via `CompletableFuture.supplyAsync` off the FX
  thread; suggestions and From-default apply on `Platform.runLater`. The search is **debounced**
  (~150ms) so each keystroke at volume doesn't hammer the DB.

---

## 6. Phasing (each slice green via `./gradlew test`)

- **P0 — the right flow, inline, safe (must-have):**
  1. **Inline To-composer** replacing the modal (kills the popup).
  2. **Lead autocomplete** (`ContactSuggestion` + `ContactSuggestionCell`), raw-number fallback row.
  3. **`resolveAndOpen` unification** — `onNewMessage` and the call→Message path share one resolver;
     existing thread opens in place (no fork).
  4. **From selector, lead-aware default** (`FromNumberDefault` + `SmsService.threadNumber`), applied
     on every thread open.
  5. **DNC at pick time** — badge in suggestions + the already-built opt-out banner / disabled compose
     in the opened thread.
- **P1 — less friction, no orphans:**
  6. **Create/link lead** inline for unknown numbers.
  7. **Recent-leads / recently-called shortlist** on the empty To-field.
  8. **From default v2** — call-continuity + local-area-code match (reuse `CallerIdSelector`).
  9. **Templates** available on the first message (rides the redesign's templates item).
- **P2 / later:**
  10. Full **"To:" token field** polish (multi-token visuals) — only meaningful alongside multi-recipient.

---

## 7. Testing
- **Tested (headless):** `ContactSuggestionTest` (lead display/subtitle/dnc; raw-number row; blank
  guards), `FromNumberDefaultTest` (continuity wins → pinned → first → empty; ignores numbers not in
  owned). Service: `SmsService.threadNumber` returns the most-recent message's number, empty when no
  thread (in-memory DB / mock).
- **Not tested:** the inline composer, `ContactSuggestionCell`, FXML (JavaFX views).
- Coverage per `AGENTS.md` (services ≥90%, UI support is the tested surface).

---

## 8. Deliberately deferred / NOT building (YAGNI)
- **Multi-recipient / broadcast** — separate campaign surface (throttling, rotation, opt-out footer,
  per-number caps). Keep it far from this button.
- **Per-number reputation logic inside this flow** — belongs to number management; this flow only
  *consumes* assignment/continuity hints.
- **Scheduling / send-later.**
- **Per-client/tag → number mapping** as a From signal — leads have tags but no number assignment yet;
  revisit when that model exists (would slot into `FromNumberDefault`).

---

## 9. Risks / traps (ranked — design against these)
1. **Silent wrong-From send** (highest severity) — inheriting From invisibly breaks thread continuity
   and cross-contaminates client number reputation, unseen. *Fix: From explicit + lead-aware default in
   the flow and on every thread open.*
2. **DNC texted because the guard is send-time only** — legal exposure that currently fails safe by
   luck. *Fix: badge at pick + banner/disabled compose at open.*
3. **Fat-fingered E.164 → text to a stranger** — no verification loop. *Fix: picker-first; for a raw
   number, echo the resolved/created lead name back before the rep commits.*
4. **Duplicate / unseen existing thread** — same person, history not surfaced, broken roll-up. *Fix:
   route through `openConversation`, which selects the existing thread first.*
5. **Wrong "Dave"** at volume — *Fix: every suggestion row shows name · company · number · DNC, never
   a bare name.*
- **Open question:** does `MessagesController` get a `CallerIdSelector` (for From v2 call-continuity /
  local presence) injected, or stay on the simpler continuity→pinned→first default for v1? Recommend
  v1 simple, inject in P1.
