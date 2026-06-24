# Messages (SMS) Redesign — make it arrive, tell the truth, say a name

> One line, taped to the monitor (the buyer floor's words): **make it arrive on its own, make it
> tell the truth about delivery, and make it say a name instead of a number — then worry about
> templates and bubbles.**
>
> Scope: `ui/` + `services/` (+ a thin `app/` wire). The auto-receive plumbing already exists and
> is merely disabled. **Plan only — do not implement.**

---

## Status

**P0 implemented (build + tests green).** K auto-receive (background poll re-enabled, DND-aware
toast + sidebar dot), B delivery truth (outbound persists Failed; bubbles show Sending/Sent/Failed +
Retry), A lead identity (avatar + name + company + flag + local time in list & thread header), H
compliance (DNC blocked at compose + inbound STOP auto-opt-out + thread banner), Enter-to-send, and
the needs-reply triage state. New headless (tested): `OptOutDetector`, `SmsSegments`,
`ConversationReplyState`, `SmsStatusLabel`, `SmsConversationRow`, `Avatars`. **P1/P2 below remain.**

---

## 0. Buyer-agent validation (verdict that shaped this plan)

Stress-tested with the `buyer` agent (6 cold-calling buyers/operators). The blunt headline:

> "**Auto-receive isn't a feature on this list — it's the foundation the whole screen stands on.**
> A Messages screen where inbound only appears when someone clicks Refresh is not a messaging
> product; it's a sent-items log with a reply box. After that, the only two things that earn their
> keep day one are **lead identity** and **failed-SMS visibility** — a silently failed follow-up
> and a reply you never saw are the two ways this screen actively *costs us money*."

Rulings:
- **Auto-receive (K) is P0 #1.** Manual Refresh is "the cardinal trap" — it makes the rep responsible
  for a job the system should do, and it's *misleading* (an empty thread reads as "no replies" when
  it's really "not fetched"). It also lights the sidebar unread dot we already wired (dark until this
  ships).
- **Failed-send visibility (B) is the one P0 shippable before K** — outbound delivery state doesn't
  depend on the inbound relay. "A follow-up that fails silently makes a rep lie to their manager
  unintentionally — the single worst correctness bug on the screen."
- **Lead identity (A) is table stakes** — "raw +E.164 in the list is amateur hour; my reps know
  *Acme / Dave*, not numbers." Cheap (we already resolve lead+flag+local-time on Leads/Call History).
- **Compliance (H) is a legal floor, not a feature** — STOP/opt-out must be **auto-enforced** (system
  behavior, not rep discretion), and a DNC number must be blocked at compose just like the dial path.
  "Texting someone who replied STOP is a TCPA complaint with my client's name on it."
- **Differentiator = G** (call-from-thread + last-call disposition inline): "SMS is a follow-up to a
  *call* — show the outcome right there and let me redial. Nobody else builds the call<->text<->outcome
  loop." P1.
- **Sequencing, blunt:** do **not** ship A/C/unread/notifications (inbound-flavored UI) while inbound
  still needs a button — you'd build UI for data that doesn't arrive. K first (or lockstep); if K were
  truly blocked, B is the only honest interim. **K is not blocked here** (see 1.1).

**Three to ship first:** (1) **K** auto-receive, (2) **B** delivery status + retry, (3) **A** lead
identity. Bundle **H** (opt-out + DNC-on-send) as close to P0 as engineering allows.

---

## 0.1 VA-agent validation (daily operator, 6-8h/shift — second lens)

Run past the `va` agent (SDRs/VAs/managers who live in the screen). Verdict: "the P0 four are right,
but this was written for *messaging-product correctness*, not for *hour 6 with 47 threads open*."
Six changes before we lock it:

1. **The #1 hole isn't on the list: "I read it but haven't replied yet."** Mark-read-on-open is a
   trap — open Dave's "what's the link?", a call rings, you jump, and Dave now looks *handled*. He
   isn't. A thread is **done only when MY last message is outbound** (or it's opted out); inbound-last
   = still on me. Need a **third state (unread -> opened/unreplied -> replied)** + a **"Needs reply"
   filter/sort** on the left list. **Promote into P0.**
2. **Enter-to-send is P0, not P1.** Highest-frequency keystroke on the screen (x hundreds/day), nearly
   free. Pull the send half of F out of P1.
3. **Focused-thread fast poll (~3-5s), background 15s.** 15s is fine for async follow-up but breaks a
   live back-and-forth (30s felt round-trip -> double-sends "you there?"). Poll the *open* thread fast,
   the other 60 at 15s. Live-exchange felt threshold ~5s.
4. **Label outbound "Sent", not "Delivered".** Send-success only means Twilio *accepted* it, not that
   the carrier delivered it. At hour 6 a rep swears "it says Delivered" and it never landed. Reserve
   "Delivered" for a real receipt later.
5. **From-default = thread-continuity, not last-call.** Default to the number *this thread already
   used*; fall back to last-call only for a brand-new thread. Else you fragment the thread / send from
   a number the lead never saw (worst for VAs juggling 6 numbers across clients).
6. **Notification discipline** (heads-down dialing all day): **DND during a live call** (no
   sound/focus-steal — queue it, light the dot, tell me after), **batch** ("3 new messages" not 3
   toasts), **persistent + clickable** (jumps to the thread; a toast that vanishes in 4s mid-dial =
   never seen), **soft chime only when off-call**.

Re-ranked by the operator: **templates (E) move P2 -> P1** (the biggest time-saver after K for a
volume texter — "I send the same 3 follow-ups all day"); **segment counter -> a boundary *warning***
only (don't build an always-on GSM-7/UCS-2 readout — warn near 160 / on Unicode); **search (D) stays
P2** but a **"Needs reply" filter is P1**, not search-tier. Two missing loop pieces to add:
**text-from-dialer after a no-answer** (80% of texts start there, not from a thread) and **keyboard
nav between threads** (Up/Down or j/k + Enter — mouse-hunting 60 threads is death by a thousand
clicks). Also missing for week one: **snooze / follow-up reminder**, **quick-disposition from a
reply**, and a **`{link}` token** in templates (calendar links pasted all day).

---

## 1. Current state

`MessagesController` + `messages-view.fxml`: two panes.
- **Header:** "Messages" + a manual **Refresh** (`smsService.refreshInbound()`); automatic polling is
  off.
- **Conversations** (`ConversationListCell`): **raw +E.164** (bold) + `HH:mm` (no date) + a `->/<-`
  arrow + truncated preview. No name/company, no flag, no unread, no status, no search.
- **Thread** (`MessageBubbleCell`): header = raw number, subtitle "SMS thread"; in/out bubbles; no
  delivery status, no date grouping, no lead/call context.
- **Compose:** "From" owned-number dropdown + field + Send (Enter does **not** send; no segment
  counter; no DNC/opt-out check). **New** = a modal dialog.

## 1.1 What already exists to reuse (so K + B + A are cheap)
- **`SmsService.startReceiving(consumer)` / `stopReceiving()` + a background `poller`** already
  implement periodic inbound polling — it's just **not started** (the call is commented out in
  `ColdCallingApp`). **Auto-receive = re-enable it** with a UI callback. No AWS relay required; the
  WebSocket relay (per `AGENTS.md`) is a later low-latency upgrade.
- **`SmsMessage` carries `status` (Pending / Delivered / Failed)** and an optional **`leadId`** — both
  unsurfaced today. `send` returns `Result<SmsId>` (ok/err) so a failed send is already knowable.
- **Lead + country resolution** is the same we built for Call History (`leadService.findByPhone`,
  `CountryLookup.byE164`, `FlagImages`, local-time formatting) — reuse it.
- **DNC** is `leadRepo.findByPhone(number).dnc()` (already enforced on the dial path in `CallService`).
- **Sidebar** already exposes `notifyInboundSms()` / `markMessagesSeen()` (wired, dark) + a toast
  layer for desktop notification.

---

## 2. Locked product decisions

- **Auto-receive is the product, P0 #1.** Re-enable `SmsService.startReceiving` (background poll) with
  a callback that, on new inbound: reloads conversations + the open thread, **lights the sidebar
  unread dot**, and raises a **desktop toast**. Poll the **open thread fast (~3-5s)** and the rest in
  the **background at ~15s** (VA: 15s alone breaks a live exchange -> double-sends). Notification
  discipline: **DND during a live call** (no sound/focus-steal), **batch** multiple inbounds,
  **persistent + clickable** toast, **soft chime only off-call**. The relay is a future upgrade, not a
  v1 requirement.
- **Delivery truth is non-negotiable — but call it "Sent", not "Delivered".** Outbound bubbles show
  **Sending / Sent / Failed**; Failed is **loud** with a one-click **Retry**. Send-success only means
  the provider *accepted* it — reserve "Delivered" for a real receipt later. A failed `send` persists
  a `Failed` status (not optimistic "Delivered"). Shippable independent of K.
- **Read != replied (VA's #1 ask).** A thread is **done only when the last message is outbound** (or
  opted out); inbound-last = still owed a reply. Opening a thread clears the *unread* bold but NOT the
  **needs-reply** state. The left list must triage on **"who is waiting on me?"** — a Needs-reply
  filter/sort + visible Failed and Opted-out states — not just a prettier name+flag row.
- **From-default = thread-continuity.** The compose "From" defaults to the number **this thread already
  used**; last-call number is only the fallback for a brand-new thread.
- **Identity over digits.** Conversations and the thread header show **name + company + flag + local
  time**, resolved like Call History; raw number is secondary. Click-through to the lead / call.
- **Familiar-first = zero learning curve (Apple HIG + iMessage).** Every cold caller already lives in
  **iMessage / WhatsApp**, so we copy those conventions verbatim: two-pane like **macOS Messages.app**
  (this *evolves* today's layout, not a rewrite), **blue right-aligned** outbound bubbles + **grey
  left-aligned** inbound, a **pill compose bar** pinned bottom, **monogram avatars**, an unread dot,
  and an iMessage-style status caption under the last sent bubble. AtlantaFX/Inter, 8pt grid, light/dark
  auto per `AGENTS.md`. **If iMessage does it a certain way, we do it that way** — no novel interaction
  to learn, nothing to read a manual for. Ultra-simple: one primary action visible at a time,
  whitespace over density, color only for accent/success/warning/error.
- **Compliance is a system behavior, not a toggle.** (a) **Block send to a DNC/opted-out number at
  compose** (same enforcement as dialing) with a clear reason; (b) **inbound STOP/UNSUBSCRIBE/...
  auto-marks the lead do-not-text** and is enforced for *every* rep thereafter; (c) the thread shows
  an **"Opted out"** banner. Humans forget; the system must not.
- **Don't ship inbound-flavored UI before K.** Unread, notifications, and "who replied" wait on
  auto-receive so we never render a queue for data that can't arrive on its own.
- **Keep `MessagesController` <=250 lines** -> extract the conversation/thread view-models, the segment
  counter, and the opt-out detector into headless (tested) classes; cells stay view-only.

---

## 3. Architecture & components (bottom-up)

### 3.1 Domain — sufficient
- `SmsMessage(status, leadId, ...)` and `SmsStatus` (Pending/Delivered/Failed) already exist. Do-not-text
  reuses the lead `dnc` flag (calls + texts share one suppression list) — **decision:** one
  do-not-contact flag, not a separate SMS opt-out column (avoids two-truths; see 9 if a text-only
  opt-out is later required).

### 3.2 Services (`services/`)
- **`SmsService.send`** — **enforce DNC/opt-out first** (mirror `CallService.isDnc`); return a clear
  `Result.err("...on do-not-contact")` that the compose bar surfaces. Persist a **`Failed`** status on a
  provider error (stop the optimistic "Delivered").
- **Inbound opt-out** — in `persistInbound`, if the body matches an opt-out keyword
  (`OptOutDetector`), mark the lead do-not-text (`leadRepo` update) and stamp the thread. Honor the
  carrier's own STOP handling too (don't double-send).
- **`startReceiving`** is re-enabled by `app/` with a UI callback (no service change beyond the
  opt-out hook).
- *(P1)* a **resend/retry** path for a `Failed` message (re-`send` same from/to/body).

### 3.3 UI (`ui/`)
- **`MessagesController`** — add `leadService` (resolve identity), wire **Enter-to-send**, the
  **segment counter**, the **opt-out/DNC compose guard**, and the auto-receive refresh entry point.
  Off-thread loads -> `Platform.runLater`.
- **`ConversationListCell`** -> lead-aware: name + company, flag, **relative date** (Today/Yesterday/...),
  **unread** bolding/dot, last-status glyph. Mirrors `CallRowCell`.
- **`MessageBubbleCell`** -> **delivery status** under outbound bubbles (Sending/Delivered/Failed +
  Retry); **date separators**; opt-out system note.
- **Thread header** -> name + company + flag + local time + **Call** button + **last-call disposition**
  chip (G); "From" defaults to the number **this thread already used** (last-call only for new threads).
- **Compose** -> **Enter sends** (P0; Shift+Enter = newline); a **segment boundary warning** (not an
  always-on counter) near 160 / on Unicode; disabled with a reason when the number is do-not-contact.
- **Left list triage** -> a **"Needs reply" filter/sort** + visible **Failed** and **Opted-out**
  states; **keyboard nav** (Up/Down or j/k + Enter) so hands stay off the mouse across 60 threads.
- **Inbound notification** -> `MainWindow` lights the sidebar dot + a toast on the auto-receive
  callback; opening Messages clears the dot (`markMessagesSeen`).

### 3.4 Headless support (`ui/support/` — **unit-tested**)
- **`ConversationReplyState`** — derive **unread / opened-unreplied / done** from a thread:
  inbound-last (after last seen) = **needs reply**; outbound-last or opted-out = **done**. Drives the
  Needs-reply filter. Pure, tested. *(The VA's #1 ask — P0.)*
- **`SmsSegments`** — a **boundary warning**, not an always-on readout: `warn(body)` -> none /
  near-limit (>140 GSM-7) / will-split (>160) / unicode. Pure, table-tested.
- **`OptOutDetector`** — `isOptOut(body)` for STOP/STOPALL/UNSUBSCRIBE/CANCEL/END/QUIT (case/space
  -insensitive) and `isOptIn(body)` for START/UNSTOP. Pure, tested. (Lives in `services` if the
  inbound hook needs it server-side — pick the module that owns `persistInbound`.)
- **`SmsConversationRow`** — view-model: remote, `Optional<Lead>`, `Optional<Country>`, last message,
  direction, `SmsStatus`, `unread`, `replyState`, `optedOut`. Built off-thread (mirrors
  `CallHistoryRow`).
- **`SmsStatusLabel`** — `label(SmsStatus)` -> **Sending / Sent / Failed** + style class. Small, tested.

---

## 4. UX / visual spec

### 4.0 Design language (Apple HIG + familiar messaging — ultra-simple)

**Mental model = macOS Messages.app.** Copy iMessage/WhatsApp conventions verbatim so there's nothing
to learn. Evolves the current two-pane; doesn't reinvent it.

- **Layout:** two-pane, fixed. Left = conversation list (~320px). Right = the open thread with the
  compose bar pinned to the bottom. Right pane shows a single calm empty-state until a conversation is
  picked (`bi-chat-dots` + "Select a conversation").
- **Bubbles (iMessage):** outbound = **accent blue**, right-aligned, white text; inbound = **secondary
  grey** (`--color-bg-secondary`), left-aligned, primary text. **Radius 18px**, max width ~70% of the
  pane, 4px gap within a sender run / 12px on sender change. No tails (Messages.app desktop dropped
  them — cleaner).
- **Status line (iMessage):** a small grey caption under the **last outbound bubble only** —
  **"Sending…" / "Sent" / "Failed · Retry"** (like iMessage's "Delivered"). Never a badge on every
  bubble.
- **Conversation rows (Messages.app):** circular **monogram avatar** (initials + deterministic color;
  reuse the Leads/Call-History avatar helper if one exists, else a tiny shared `Avatars`), **name**
  (15px/600), one-line **preview** (13px/secondary, truncated), **time** top-right (12px/secondary),
  **unread = filled accent dot** + name in 600, selected row = subtle accent tint. ~64px rows, 8pt-grid
  padding. Flag + local time sit with the name (identity, P0).
- **Compose bar:** a **pill text field** (radius 18px, `--color-bg-secondary` fill, grows to ~5 lines)
  + a circular **paper-plane send** (`bi-send`, accent), pinned bottom, 12px padding. **Enter sends**,
  Shift+Enter = newline.
- **Type & grid:** Inter; HIG scale from `AGENTS.md` (15px body, 13px label, 12px caption); 8pt grid;
  light/dark auto; phone numbers in `--type-mono`.
- **Icons (SF-Symbols stand-in = Ikonli `bi-*`):** `bi-send`, `bi-telephone` (Call), `bi-check2`
  (Sent), `bi-exclamation-circle` (Failed), `bi-slash-circle` (Opted-out), `bi-chat-dots` (empty).
- **Familiar affordances:** click / Enter opens a thread; **right-click context menu** (Call · Copy
  number · Mark unread · Open lead) instead of inventing buttons; **↑/↓** move between conversations.
- **Restraint (ultra-simple):** no toolbar clutter, status as a glyph not a badge wall, one primary
  action in view, generous whitespace — readable at a glance at hour 6.

### 4.1 Conversations (P0 lead identity + P1 unread/date)
```
*  Dave - Acme Corp                         Today 3:41 PM
   US  <- thanks, what's the link?                 (unread, bold)
   Priya Patel                              Yesterday
   IN  -> sending my calendar now (Sent)
```
- Name + company (raw number on hover / secondary); flag; **relative date**; unread dot+bold; last
  message + its status glyph. **Triage states visible: Needs-reply (inbound-last), Failed, Opted-out**
  + a **"Needs reply" filter/sort** (P1). Search box at top (P2).

### 4.2 Thread (P0 status + P1 context)
- Header: **Dave - Acme Corp - US 3:41 PM PST - [Call] - last call: Interested (2d ago)**.
- Bubbles (iMessage style): outbound **blue / right**, inbound **grey / left**; an iMessage status
  caption under the **last outbound bubble only** — **Sending... / Sent / Failed - Retry** (never
  "Delivered" without a real receipt); **date separators**; an **"Opted out — texting disabled"**
  system note when the lead replied STOP.

### 4.3 Compose (P0 guard + Enter-to-send, P1 ergonomics)
- "From" defaults to the number **this thread already used** (last-call only for a new thread).
  **Enter sends** (Shift+Enter = newline). A **boundary warning** appears only near 160 / on Unicode
  ("will split into 2" / "Unicode"), not a constant counter. When the number is do-not-contact, the
  field is disabled with **"This number opted out / is on DNC."**

### 4.4 Inbound arrival (P0 K + notification discipline)
- New inbound (auto-received) -> conversation jumps to top, **sidebar unread dot lights**. Notification
  is **batched** ("3 new messages"), **persistent + clickable** (jumps to the thread), a **soft chime
  only when off-call**, and **fully suppressed (DND) during a live call** — queue it, light the dot,
  surface after. Opening Messages clears the *unread* bold but not a thread's **needs-reply** state.

---

## 5. Threading
- `startReceiving`'s poll runs on its own scheduler (off the FX thread); its callback marshals to the
  FX thread via `Platform.runLater` for UI + sidebar + toast. **Two cadences:** the currently-open
  thread polls **~3-5s** (live exchange), all background conversations **~15s** (cost vs latency).
  Notification raise is **suppressed while a call is active (DND)** — queue + light the dot, chime/toast
  when off-call. All `send` / `refreshInbound` / identity resolution already run via `CompletableFuture`.
  The compose DNC check runs off-thread (DB read) before dispatch, like the dial path.

---

## 6. Phasing (each slice green via `./gradlew test`) — VA-reordered

- **P0 — true on day one (works · truthful · named · compliant · no lost replies):**
  1. **K — Auto-receive:** re-enable `startReceiving` in `app/` with a callback ->
     reload + sidebar dot + toast; focused-thread ~3-5s / background ~15s; DND on active call.
  2. **B — Delivery status + retry:** persist real `Failed` on send error; render
     **Sending / Sent / Failed** (NOT "Delivered") on bubbles + Retry; `SmsStatusLabel` (tested).
  3. **A — Lead identity:** `SmsConversationRow` (tested) + lead/flag/local-time in list + thread
     header (reuse Call History resolution).
  4. **H — Compliance:** DNC/opt-out **block at compose**; inbound `OptOutDetector` (tested) ->
     mark lead do-not-text + thread banner.
  5. **F (Enter-to-send only):** pulled out of P1 — highest-frequency keystroke, near-free
     (Shift+Enter = newline).
  6. **Needs-reply state:** the **opened-but-unreplied** tier — "done" only when last message is
     outbound. `ConversationReplyState` (tested). Without it the screen hides dropped follow-ups.
- **P1 — triage + the cold-calling-native loop + ergonomics:**
  7. **C — Full unread/replied model + "Needs reply" filter/sort** on the left list (truthful, no
     lying badge).
  8. **G — Call<->text loop:** Call button + last-disposition chip in the thread header, **plus a Text
     button on the dialer/no-answer screen** (the 80% direction).
  9. **E — Templates / quick replies** (moved up from P2) with first-name + **`{link}` token**.
  10. **Keyboard nav between threads** (Up/Down or j/k + Enter to open).
  11. **I — Date + relative grouping** in list and thread.
  12. **From-default = thread-continuity** (last-call fallback only for new threads);
      **unknown-number -> link/create lead**; **quick-disposition from a reply**.
  13. **Segment boundary *warning*** (`SmsSegments`, tested) — warn near 160 / on Unicode; not an
      always-on readout.
- **P2 — quality of life:**
  14. **D — Search** conversations (name/company/number).
  15. **J — Inline New** (autocomplete) replacing the modal.
  16. **Snooze / follow-up reminder** ("text Dave tomorrow 9am").
  17. Full always-on segment readout (if anyone still wants it).

---

## 7. Testing
- **Tested (headless):** `SmsSegmentsTest` (**boundary warning**: <=160 GSM-7 quiet, 161 warns,
  Unicode warns — not a full UCS-2 readout), `OptOutDetectorTest` (STOP/UNSUBSCRIBE/case/whitespace;
  START opt-in; non-matches), `SmsConversationRowTest` (identity, unread, status, optedOut),
  `ConversationReplyStateTest` (**inbound-last = needs reply; outbound-last / opted-out = done; open
  clears unread but not needs-reply**), `SmsStatusLabelTest` (Sending/Sent/Failed labels + styles).
  Service: `SmsService.send` **blocks a DNC/opt-out number**; inbound poll **marks opt-out** on a STOP
  body; failed send **persists Failed** (mock Twilio).
- **Not tested:** `MessagesController`, cells, FXML (JavaFX views).
- Coverage per `AGENTS.md` (services >=90%, UI support is the tested surface).

---

## 8. Deliberately deferred / NOT building (YAGNI)
- **AWS WebSocket relay** for sub-second inbound — the background poll covers v1; the relay is a
  latency upgrade, separate infra.
- **CRM (Salesforce) sync** — out of scope for this screen (Jake's need, separate integration).
- **MMS / attachments**, group threads — voice+SMS app, 1:1 text only for v1.
- **Chat-bubble visual polish for its own sake** — the buyer's named "vanity"; spend nowhere here.
- **A separate SMS-only opt-out list** distinct from DNC — one do-not-contact flag in v1 (see 9).

---

## 9. Risks / open questions
- **K depends on Twilio inbound actually being fetchable** (`twilio.fetchInboundSince`). The poll
  mechanism is bounded, but in dev with no inbound it yields nothing — verify against a live Twilio
  number. **Two-cadence poll** (open thread ~3-5s, background ~15s) per the VA: 15s alone breaks a
  live exchange -> double-sends. Settle exact intervals + API-cost ceiling in review.
- **One do-not-contact flag (dnc) for calls + texts** is the simple, safe v1. If product later wants
  "OK to call, not to text," that's a dedicated `sms_opt_out` column (small migration) — flagged, not
  built.
- **"Delivered" on send-success is a lie** — label outbound **"Sent"** (provider accepted) and persist
  `Failed` loudly on error; reserve "Delivered" for a real Twilio status-callback receipt later.
- **Mark-read-on-open hides unreplied threads** (VA's #1 trap) — the needs-reply state (P0) is the
  fix; "done" = last message outbound, not "opened".
- **Notification at hour 6:** a toast that vanishes mid-dial = never seen; a toast/sound during a live
  call = fat-fingered notes. v1 = in-app toast that is **batched, persistent, clickable, DND-on-call,
  chime only off-call**. True OS/Dock notification (minimized window) = same gap as the sidebar plan,
  track together.
- **Unknown inbound number** (no lead) must not orphan — P1 "link to lead / create lead"; until then
  it shows as the raw number (acceptable interim, but don't let it silently lose the reply).
