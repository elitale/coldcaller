# Plan — Call Routing (outbound PSTN bridge) in Settings + Onboarding

> Status: **PLANNED** (not started). Plan only — no code yet. Build bottom-up, TDD.
> Layer order per AGENTS.md: domain → providers/telephony → services → ui → app.

---

## 0. Terminology — what "the rtp server" is

The user's "rtp server" = the **outbound PSTN bridge / Voice webhook** we built as an
untracked operator script:

- `.scripts/sip-pstn-handler/pstn-bridge.js` — a Twilio Serverless Function. When a
  registered softphone (this app) dials `sip:+1XXXXXXXXXX@<domain>`, Twilio invokes this
  webhook; it strips the SIP To/From to E.164 and `<Dial><Number>`s the PSTN, using the
  caller's own number as caller ID.
- `.scripts/setup-sip-pstn-handler.js` — deploys that function and points the SIP
  Domain's **VoiceUrl** at it.

It is the server-side piece that lets the RTP/voice path actually reach the callee. Without
it a Programmable Voice SIP Domain answers every registered-client INVITE with **404 Not
found** (see `/memories/repo/telephony.md`).

**The gap:** `TwilioClient.autoProvisionSip()` creates the SIP domain + credentials but
**never sets the domain's VoiceUrl**, and the bridge is deployed only by the manual Node
script. So even after onboarding's "Auto-configure SIP", outbound calls do not bridge to
PSTN until an operator runs the script by hand. Nothing about routing is in the app's
Settings or onboarding, and it is hardcoded to Twilio + the `elitale.sip.twilio.com` domain.

**This feature** brings call routing into the app: a provider-agnostic **Call Routing**
config, surfaced in **Settings** and as a new **onboarding step**, that ensures each
account's SIP domain points at a working PSTN bridge.

> Confirmed by the user (2026-06-23): "rtp server" = the **PSTN bridge**, not a media/RTP relay.

### 0.1 Verified live state (2026-06-23, domain `SDc9954a5a31a9a7a95cb9ea0e7cd9e7f9`)
```
domain_name       : elitale.sip.twilio.com
secure (TLS+SRTP) : true
sip_registration  : true
voice_url         : https://coldcalling-sip-3647-prod.twil.io/pstn-bridge
voice_method      : POST
auth_type         : CREDENTIAL_LIST
emergency_calling : true
```
Implications for the plan:
- The bridge is **already deployed + wired** on the project's own account — so the
  `readSipDomainVoiceUrl` reflect-step (§4.2/§9) must show this account as *already
  configured* (Manual, adopting the existing URL) rather than "not configured".
- That hosted function (`coldcalling-sip-3647-prod.twil.io/pstn-bridge`) is a **`protected`
  Twilio Function bound to this account** — it cannot be invoked by *other* users' Twilio
  accounts. Confirms **AUTO-for-arbitrary-accounts needs Option A or B (§2); you cannot just
  reuse this URL** for every customer.
- `secure=true` + `auth_type=CREDENTIAL_LIST` must be preserved — routing only sets the
  `VoiceUrl`; it must not touch the secure-media or credential-list config.

---

## 1. Goal

1. **Settings → Call Routing** section: view + set the outbound bridge for the active
   provider (Auto-configure, or paste a Voice webhook URL), with a live status line.
2. **Provider-agnostic**: the config model + UI are driven by the selected provider, not
   hardcoded to Twilio. Twilio gets a working implementation; the other catalog providers
   (Vonage/Telnyx/Plivo/Bandwidth — today "Coming soon") slot in behind the same seam.
3. **Onboarding**: insert a **"Routing"** step (after *Connection/SIP*, before *Numbers*)
   that configures the bridge, mirroring the SIP step's "Set it up for me" + manual fields.

---

## 2. How routing is applied (the hosting fork — confirm before Phase 3)

Routing needs a **Voice webhook URL** that the provider invokes per outbound call and that
returns dial instructions. Three ways to obtain/host it:

| Option | What the app does | Pros | Cons |
|---|---|---|---|
| **A. Operator-hosted neutral bridge (recommended)** | App points the account's SIP domain `VoiceUrl` at **one** webhook we host on the existing AWS `infra/` (API Gateway + Lambda), which returns provider-appropriate markup (TwiML now; TeXML/NCCO/XML later). | One SDK call to apply; reuses existing infra; truly provider-agnostic; no per-account serverless deploys; billing always lands on the calling account. | New (small) infra + endpoint hardening (signature-tolerant validation + rate limit). |
| **B. Per-account serverless deploy (current script)** | App reproduces `setup-sip-pstn-handler.js` in Java: deploy a `protected` Function on the **user's** Twilio account, then set VoiceUrl. | Mirrors what we shipped; `protected` ⇒ only that account can invoke. | Heavy: multipart upload to `serverless-upload.twilio.com`, build polling, env deploy — fragile from a desktop app; Twilio-only. |
| **C. Manual URL (always available as escape hatch)** | User pastes an existing Voice webhook URL; app stores it and (Twilio) applies it to the SIP domain. | Trivial; works for any provider; unblocks power users. | User must already have a bridge deployed. |

**Recommendation:** ship **A** (managed/auto) **+ C** (manual override) first. Treat **B**
as optional/Phase-3b only if an operator-hosted bridge is unacceptable.

> Security (carry forward from `/memories/repo/telephony.md`): never expose a bridge as a
> Twilio **public** Function that can place PSTN calls — toll-fraud risk. A `protected`
> Function is per-account (rules out a shared one); the operator-hosted AWS endpoint is the
> neutral middle ground. Keep secure SIP intact — **do not** change `secure=true` /
> TLS+SRTP; routing only sets where the call is bridged, not the media profile.

---

## 3. Locked product decisions

- **Two modes**, captured by `CallRoutingMode { NONE, MANUAL, AUTO }`:
  - `AUTO` — app resolves + applies a managed bridge URL for the provider (Twilio: set SIP
    domain VoiceUrl to the hosted/known bridge). One click.
  - `MANUAL` — user supplies the Voice webhook URL; app validates (`https://`) + applies.
  - `NONE` — unconfigured (default for accounts onboarded before this feature).
- **AUTO availability is per provider.** Today only Twilio. Non-Twilio providers expose
  `MANUAL` (store-only until that provider's apply path exists) and show AUTO disabled.
- **Routing depends on SIP.** The onboarding Routing step comes **after** the SIP step so
  the SIP domain already exists to point at. In Settings, Call Routing sits **below SIP**.
- **Single active provider** ⇒ flat settings keys (no per-provider namespacing yet). The
  stored `providerId` records which provider the config belongs to (defaults to `twilio`).
- **Onboarding wizard becomes 5 steps**: Provider · Account · Connection · **Routing** ·
  Numbers. Routing is **skippable** (a `Skip for now` flat button) — a misconfigured bridge
  must not trap a user out of finishing setup; Settings can fix it later. (SIP test stays
  required; routing does not.)
- **Persistence timing** unchanged: drafts saved per successful step; the final commit in
  `completeOnboarding` writes the routing config alongside creds/numbers and the completion
  flag last.
- **Idempotent + non-destructive**: applying AUTO/MANUAL overwrites only the VoiceUrl on the
  resolved SIP domain; it never deletes credentials, domains, or numbers.

### Decisions to confirm
1. **Hosting fork** (§2): A (recommended) vs B vs C-only.
2. **Step placement**: Routing as step 4 (after Connection) — confirm vs after Numbers.
3. **Extend `ProviderOption`** with `boolean supportsAutoRouting` to drive UI gating
   declaratively (small record + test change), vs keep gating in the service. Recommended:
   add the field (keeps UI dumb).

---

## 4. Architecture & new components

### 4.1 Domain (`domain/`)
- **`CallRoutingMode`** (enum: `NONE, MANUAL, AUTO`). A 3-constant mode with no payload —
  an enum is the KISS choice (no sealed interface needed; AGENTS §5.1 sealed-for-sum-types
  is for variants carrying data). Package `domain.routing`.
- **`CallRoutingConfig`** (record, ≤100 lines) — `String providerId, CallRoutingMode mode,
  String voiceUrl, String callerIdFallback`.
  - Compact-constructor validation: non-null all; if `mode != NONE` then `voiceUrl` required;
    if `voiceUrl` non-blank it must start `https://`. `callerIdFallback` may be blank.
  - Helpers: `isConfigured()`, static `none(providerId)`.
- *(Optional, decision 3)* add `boolean supportsAutoRouting` to **`ProviderOption`** +
  update `ProviderOptions.ALL` (Twilio `true`, rest `false`) + its test.

### 4.2 Providers (`providers/twilio/`)
- **`TwilioClient.setSipDomainVoiceUrl(String domainName, String voiceUrl, String voiceMethod)`
  → `Result<Void>`** — find the domain by name (reuse the `Domain.reader()` loop pattern from
  `autoProvisionSip`), then
  `Domain.updater(sid).setVoiceUrl(URI.create(voiceUrl)).setVoiceMethod(HttpMethod.POST)
  .update(restClient)`. Returns `Result.err` on Twilio/network failure (never throws), like
  every other method here.
- **`TwilioClient.readSipDomainVoiceUrl(String domainName) → Result<Optional<String>>`** —
  so Settings can reflect the *actual* current VoiceUrl (detect already-configured accounts,
  incl. the live `elitale.sip.twilio.com` the script set).
- *(Option B only)* **`TwilioVoiceBridgeProvisioner`** (new class) + bundle
  `providers/src/main/resources/twilio/pstn-bridge.js` as a classpath resource; port the
  serverless deploy from `setup-sip-pstn-handler.js`. **Deferred** unless §2 chooses B.

### 4.3 Services (`services/`)
- **`CallRoutingService`** (≤200 lines) — the provider-agnostic seam (mirrors how
  `OnboardingService.autoConfigureSip` delegates to `TwilioClient`):
  - ctor: `(SettingsService settings, Function<TwilioConfig, TwilioClient> twilioFactory)`
    (factory keeps it testable + decoupled from the stale singleton, exactly like
    `OnboardingService`).
  - `CallRoutingConfig load()` — read persisted config (provider, mode, url, fallback).
  - `Result<CallRoutingConfig> applyManual(String providerId, String voiceUrl, String callerIdFallback)`
    — validate; for `twilio` apply to the SIP domain (domain read from `SettingsService`);
    persist; return config. Non-Twilio ⇒ store-only + `Result.ok` (or `err("apply not yet
    supported for <provider>")` — confirm; recommend store-only so the field is usable).
  - `Result<CallRoutingConfig> autoConfigure(String providerId)` — Twilio: resolve the
    managed bridge URL (Option A: a configured default; Option B: provision) → set VoiceUrl →
    persist. Non-Twilio ⇒ `Result.err("Automatic routing isn't available for <provider> yet.")`.
  - `Result<Optional<String>> currentVoiceUrl(String providerId)` — reflect live state in UI.
  - `switch (providerId)` is the one place that knows providers; today only `twilio` has a
    body. **This is the carve-out vs a `domain` `CallRoutingProvisioner` interface** — skipped
    on purpose (KISS/YAGNI): promote to an injected interface when a *second* provider ships
    (preference: abstract on 2+ consumers, not before).
- **`SettingsService`** additions (keys + typed accessors, defaults documented):
  - `KEY_CALL_ROUTING_PROVIDER   = "callrouting.provider"`   (default `twilio`)
  - `KEY_CALL_ROUTING_MODE       = "callrouting.mode"`       (default `none`)
  - `KEY_CALL_ROUTING_VOICE_URL  = "callrouting.voice_url"`  (default `""`)
  - `KEY_CALL_ROUTING_CALLER_ID  = "callrouting.caller_id_fallback"` (default `""`)
  - `getCallRouting()/setCallRouting(CallRoutingConfig)` round-trip helpers (parse/format the
    enum safely, fall back to `NONE`).
- **`OnboardingService`** integration:
  - `Result<CallRoutingConfig> autoConfigureRouting(String providerId)` and
    `applyManualRouting(...)` — thin delegates to `CallRoutingService` (inject it).
  - `saveRoutingDraft(CallRoutingConfig)` — persist mid-wizard like `saveSipDraft`.
  - `completeOnboarding(OnboardingResult)` also persists the routing config (before the
    completion flag, consistent with the "flag last" rule).
  - `loadDraft()` / **`OnboardingDraft`** gain routing fields (mode, voiceUrl, callerIdFallback).
- **`OnboardingResult`** gains `CallRoutingConfig routing` (built by `OnboardingModel`).

### 4.4 UI (`ui/`)
- **Settings** (`settings-view.fxml` + `SettingsController`): new `bg-elevated` **"Call
  Routing"** section under SIP:
  - Mode segmented control / radio: **Automatic** (disabled when provider lacks AUTO) ·
    **Manual**.
  - **Voice webhook URL** `TextField` (read-only/derived in Automatic; editable in Manual).
  - **Caller ID fallback** `TextField` (optional; `promptText` "+1… used when caller unknown").
  - Buttons: **Auto-configure** (Twilio), **Apply/Save**, **Refresh** (read live VoiceUrl);
    `statusLabel` shows ✓/✗ + the active URL. All Twilio calls run off the FX thread via
    `CompletableFuture` + `Platform.runLater` (AGENTS §7), with a progress affordance.
  - A help card like the SIP one ("What is this? When you dial, your provider calls this URL
    to bridge to the phone network.").
- **Onboarding** (`onboarding-view.fxml` + `OnboardingController` + `OnboardingModel`):
  - `OnboardingModel.Step` → `{ PROVIDER, TWILIO, SIP, ROUTING, NUMBERS }`; update
    `STEPS`, gating, `buildResult()` to include `CallRoutingConfig`.
  - FXML: add `dot5/name5` + `rule4` to the stepper (now 5 dots); add `step5Pane` (Numbers
    moves to pane 5) and a new **Routing** pane (auto-config row + URL/fallback fields +
    help card + a `Skip for now` flat button).
  - Controller: `render()` handles 5 panes; add `testRouting()` (apply manual/auto via
    `OnboardingService`), `onAutoConfigureRouting()`, skip handler, and gating
    (`updatePrimaryEnabled` case `ROUTING`). Keep ≤250 lines — if it grows, extract a
    `RoutingStepView` helper (AGENTS §5.6).

### 4.5 App wiring (`app/ColdCallingApp`)
- Construct `CallRoutingService` in `init()` (credential-independent: it takes the settings +
  a `TwilioClient` factory) and inject it into `OnboardingService`.
- Add `CallRoutingService` to `MainWindow.Dependencies` and a `setCallRoutingService(...)` on
  `SettingsController` (wired in `MainWindow`).
- No telephony-startup change (routing is a provider-side config, not a local SIP change).

---

## 5. Flow

```mermaid
flowchart TD
    A[Onboarding: SIP step OK] --> R[Step 4 · Routing]
    R -->|Automatic| RA[autoConfigure twilio: set SIP domain VoiceUrl → managed bridge]
    R -->|Manual| RM[applyManual: validate https URL → set VoiceUrl]
    R -->|Skip for now| RS[mode = NONE]
    RA --> N[Step 5 · Numbers]
    RM --> N
    RS --> N
    N -->|Finish| C[completeOnboarding: persist creds + routing + numbers + flag]
    subgraph Settings (anytime)
      S[Call Routing section] --> SA[Auto-configure / Apply / Refresh]
    end
```

---

## 6. Settings keys & data model (summary)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `callrouting.provider` | string | `twilio` | Provider the config belongs to |
| `callrouting.mode` | enum | `none` | `none` \| `manual` \| `auto` |
| `callrouting.voice_url` | string | `""` | Active Voice webhook URL (https) |
| `callrouting.caller_id_fallback` | string | `""` | Optional caller ID when unknown |

`CallRoutingConfig(providerId, mode, voiceUrl, callerIdFallback)` is the single value object
passed across layers; `SettingsService` is the only thing that knows the keys.

---

## 7. Implementation phases (TDD — Red → Green → Refactor)

> `./gradlew build` + `./gradlew test` green before each next phase.

1. **Domain** — `CallRoutingMode`, `CallRoutingConfig` (+ optional `ProviderOption.supportsAutoRouting`).
   Tests: validation (https rule, mode/url invariant), `isConfigured`, `none()`; catalog gating.
2. **Settings** — keys + `getCallRouting/setCallRouting` round-trip. Tests: default `NONE`;
   round-trip; unparseable mode → `NONE`.
3. **Providers** — `TwilioClient.setSipDomainVoiceUrl` + `readSipDomainVoiceUrl`. Tests with a
   mocked `TwilioRestClient`: domain-not-found → err; update success → ok; network err → err.
4. **Services** — `CallRoutingService` (load/applyManual/autoConfigure/currentVoiceUrl) with an
   injected Twilio factory. Tests: manual https validation; Twilio apply path; non-Twilio
   store-only/err; persistence.
5. **Onboarding service/model** — draft fields, `OnboardingDraft`/`OnboardingResult` routing,
   `completeOnboarding` persists routing, `OnboardingModel` `ROUTING` step + gating + skip +
   `buildResult`. Tests: step order, skip ⇒ `NONE`, result carries config.
6. **UI — Settings** — Call Routing section + controller (off-thread apply, status, refresh).
7. **UI — Onboarding** — 5-step FXML/stepper + Routing pane + controller + CSS reuse.
8. **App wiring** — construct + inject `CallRoutingService`; extend `MainWindow.Dependencies`.
9. **Docs/cleanup** — note in-app routing replaces the manual script in
   `MEMORY.md`/`copilot-instructions` (keep the script as operator fallback); SOLID/KISS/YAGNI
   audit; full build + test; coverage targets (domain ≥95%, services ≥90%, providers ≥80%,
   controllers ≥60%).

---

## 8. Files

**New**
- `domain/.../routing/CallRoutingMode.java`, `CallRoutingConfig.java` (+ tests)
- `services/.../CallRoutingService.java` (+ test)
- *(Option B only)* `providers/.../twilio/TwilioVoiceBridgeProvisioner.java`
  + `providers/src/main/resources/twilio/pstn-bridge.js` (+ test)
- *(Option A only)* `infra/` — neutral voice-bridge endpoint (API Gateway + Lambda) returning
  provider markup; signature-tolerant + rate-limited. (Standalone TS; AGENTS §15 no `@ts-ignore`.)

**Modified**
- `providers/.../twilio/TwilioClient.java` — `setSipDomainVoiceUrl` / `readSipDomainVoiceUrl`
- `services/.../SettingsService.java` — routing keys + accessors
- `services/.../OnboardingService.java`, `OnboardingDraft.java`, `OnboardingResult.java`
- `ui/.../support/OnboardingModel.java` — `ROUTING` step + result
- `ui/.../controller/OnboardingController.java`, `ui/.../controller/SettingsController.java`
- `ui/src/main/resources/fxml/onboarding-view.fxml`, `settings-view.fxml`
- `ui/src/main/resources/css/cupertino-light.css` — reuse tokens; add only what's missing
- `ui/.../MainWindow.java` — `Dependencies` + wire `CallRoutingService` into Settings
- `app/.../ColdCallingApp.java` — construct/inject `CallRoutingService`
- *(optional)* `domain/.../onboarding/ProviderOption.java` + `ProviderOptions.java`
- `MEMORY.md` / `.github/copilot-instructions.md` — confirm before editing docs

---

## 9. Risks / open questions

- **Hosting fork (§2) is the critical decision** — it changes whether Phase 3 includes new
  AWS infra (A), a Java serverless port (B), or neither (C). App-side phases 1–8 are written
  to work regardless: they only need "a Voice URL + a mode to apply".
- **Already-configured live account** (`elitale.sip.twilio.com` → `…/pstn-bridge`): the
  `readSipDomainVoiceUrl` reflect-step prevents the UI from showing "not configured" and
  lets the user adopt the existing URL as `MANUAL` without re-deploying.
- **Non-Twilio "apply" is a stub** until those providers ship; Manual store-only keeps the
  field useful without faking capability. Don't build Vonage/Telnyx/Plivo/Bandwidth
  provisioners now (YAGNI) — only the seam.
- **Controller size**: adding a 5th step risks pushing `OnboardingController` past 250 lines
  (AGENTS §5.6) — plan to extract a routing-step helper if needed.
- **Toll fraud / secure media**: keep the bridge non-public; do not weaken TLS+SRTP.

## 10. Deliberately out of scope (YAGNI)
- Per-provider serverless deploy for non-Twilio providers.
- Per-provider namespaced settings keys (single active provider today).
- A `domain`-level `CallRoutingProvisioner` interface (promote when a 2nd provider lands).
- Inbound routing / voicemail webhooks (outbound bridge only).
- Number-capability-aware routing, multi-bridge selection.

## 11. Suggested validation
Run the `buyer` and `va` subagents against the Routing step (forced vs skippable, Auto vs
Manual, "what is a Voice webhook URL?") before building Phase 7 UI — cheap friction check.
