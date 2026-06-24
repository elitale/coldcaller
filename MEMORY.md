# MEMORY.md — coldCalling

> Persistent project memory across coding sessions. Updated by the agent at the end of significant work. Committed to git for continuity.

---

## Project Summary

**coldCalling** is a cross-platform cold calling desktop application built with Java 21 + JavaFX 21. It handles outbound/inbound SIP calls via Twilio, SMS (inbound via AWS relay, outbound direct), power dialing, local presence (multi-number rotation), call recording, and call analytics.

Target users: cold calling agencies (8–15 SDRs, 1,800–2,400 calls/day), solo freelance callers (80–100 calls/day), and B2B SaaS SDR teams.

Sister project: [`sequence`](../sequence) handles cold email campaigns. These two products form the coldBirds outreach suite.

---

## Architecture Decisions

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-06-21 | Java 21 + JavaFX 21 desktop app (not Electron/web) | Pure SIP + RTP stack; no browser; no WebRTC; lower latency; native installer via jpackage |
| 2026-06-21 | Twilio as primary telephony provider | $0.002/min vs Twilio's $0.013/min; excellent SIP + REST API; webhook support; number management |
| 2026-06-21 | Pure SIP + RTP — no WebRTC | JAIN-SIP (signaling) + jlibrtp (transport) + javax.sound.sampled (audio I/O); ~150 lines total for audio pipeline |
| 2026-06-21 | G.711 PCMU as sole audio codec | ~50 lines to implement; universally supported by all SIP providers; 8000Hz/8-bit/mono; no codec negotiation needed |
| 2026-06-21 | AWS Lambda + API Gateway WebSocket for inbound SMS relay | Twilio POSTs to HTTP endpoint → Lambda → DynamoDB stores session → WebSocket pushes to desktop; desktop reconnects on drop |
| 2026-06-21 | STUN client (~100 lines) for NAT traversal | Discovers public IP for SDP `c=` line; uses `stun.twilio.com:3478`; retries on network change |
| 2026-06-21 | SQLite embedded DB (sqlite-jdbc) + FlywayDB migrations | No server; single file at `~/.coldcalling/data.db`; jpackage bundles everything |
| 2026-06-21 | Manual constructor injection (no Spring, no Guice) | Desktop app — Spring boot-time is too heavy; CDI is overkill; wiring in `app/` module is ~200 lines |
| 2026-06-21 | Gradle 8 multi-module: domain/storage/telephony/providers/ui/app/infra | Enforces layer boundaries; `domain/` has zero external deps; `infra/` is standalone TypeScript |
| 2026-06-21 | AtlantaFX as JavaFX theming framework | Best Apple HIG implementation for JavaFX; light/dark/system auto; Inter font as SF Pro equivalent |
| 2026-06-21 | Inter font bundled in resources | Open-source visual equivalent to SF Pro; consistent rendering on all platforms |
| 2026-06-21 | OS keychain for secrets (macOS Keychain / Windows DPAPI / Linux libsecret) | Twilio API keys and SIP credentials never touch SQLite plaintext |
| 2026-06-21 | SIP REGISTER on startup with 60s refresh | Twilio routes inbound calls to the registered desktop SIP UA |
| 2026-06-21 | 8pt grid system + Apple HIG corner radii | 8px (cards), 6px (buttons), 4px (inputs); all padding multiples of 4px |
| 2026-06-23 | In-app call routing (PSTN bridge VoiceUrl) configured from Settings + onboarding | A SIP domain with no VoiceUrl answers outbound INVITEs with SIP 404. `CallRoutingService` lets users set it from the app (AUTO = deploy a per-account bridge into the user's own Twilio account via `TwilioVoiceBridgeProvisioner`, MANUAL = own URL), replacing the manual `setup-sip-pstn-handler.js` script for end-users. Both modes push the URL to Twilio (store-only would still 404). Non-Twilio = store-only seam (YAGNI) |
| 2026-06-23 | AUTO deploys a **per-account** PSTN bridge, not a shared one | The bridge function is `protected`, so Twilio validates the webhook signature against the owning account's auth token — a shared function 403s for other tenants. `TwilioVoiceBridgeProvisioner` ports the Serverless deploy to Java (injectable `Transport` over `java.net.http`, since the Twilio SDK can't do the multipart upload) and bundles `pstn-bridge.js` as a provider resource. |
| 2026-06-24 | Leads list uses **keyset (seek) pagination**, not OFFSET | Stable infinite scroll over a growing table; orders by `(created_at DESC, id DESC)` with a compound `Cursor(createdAtMillis, id)`. `Page<T>(rows, nextCursor, total)` carries the next seek key. Avoids OFFSET drift/cost. |
| 2026-06-24 | Lead facets filtered via SQLite **JSON1 `json_each`**, not `json_extract` | Tags and custom-fields are JSON columns; `json_each` lets a single key/value membership test join naturally and indexes the array elements. Custom-field filter = `json_each(custom_fields)` key+value contains. |
| 2026-06-24 | Live-phone uniqueness via a **partial unique index** (`WHERE deleted_at IS NULL`) | Soft-deleted leads keep their phone; only live rows must be unique. Enforced in DB (migration V4), surfaced as a repo-level rejection. |
| 2026-06-24 | `LeadService.NewLead` left at 8 fields (not extended for status/tags/custom) | YAGNI — quick-add + CSV import that populate those are Phase 3/4. Defer the DTO change until the import path needs it. |

---

## Completed Work

- [x] All scaffold/planning files: `AGENTS.md`, `MEMORY.md`, `.plan/coldcalling.md`, agent files, copilot instructions
- [x] Gradle multi-module scaffold: `settings.gradle`, root `build.gradle`, `gradle/libs.versions.toml`, module `build.gradle` files
- [x] **Domain layer** — 17 tests PASS: all value objects (PhoneNumber, LeadId, PhoneNumberId, SmsId, AreaCode, CallDirection, CallDisposition, NumberReputation, SmsStatus, Result), sealed interfaces (CallState, PowerDialerState, DomainEvent), entity records (Lead, OwnedNumber, Call, SmsMessage, CallList, CallListEntry, PowerDialerSession)
- [x] **Storage layer** — 15 tests PASS: `DatabaseManager` (SQLite + Flyway), `V1__initial_schema.sql`, all 5 Sqlite repositories (Lead, PhoneNumber, Call, Sms, Settings, CallList)
- [x] **Telephony layer** — 38 tests PASS: `G711Codec`, `StunMessage`, `StunClient`, `SipCredentials`, `SdpBuilder`, `SipEngine`, `SipRegistrar`, `RtpSession`, `AudioPipeline`, `TelephonyService`
- [x] **Providers layer** — 28 tests PASS: `TwilioClient`, `TwilioConfig`, `HttpSender`, `TwilioNumberData`, `SmsRelayClient`, `SmsRelayConfig`
- [x] **Services layer** — 26 tests PASS: `CallService`, `LeadService`, `SmsService`, `PhoneNumberService`
- [x] **UI layer** — `./gradlew build` green: `MainWindow`, `DialerController`, `IncomingCallController`, `ActiveCallController`, bespoke Cupertino Light CSS (~550 lines)
- [x] **`app/` module** — `ColdCallingApp` fully wired: DB → repos → providers → services → telephony → UI
- [x] **Call routing (PSTN bridge)** — 2026-06-23: `CallRoutingMode`/`CallRoutingConfig` (domain), `SettingsService` routing keys, `TwilioClient.setSipDomainVoiceUrl`/`readSipDomainVoiceUrl`, `CallRoutingService` (load/applyManual/autoConfigure/currentVoiceUrl), onboarding 5th "Routing" step, Settings "Call Routing" section, wired through `MainWindow.Dependencies` + `ColdCallingApp`. In-app config replaces the manual `.scripts/setup-sip-pstn-handler.js` for end-users (script kept as operator fallback). Full build + test green.
- [x] **Per-account AUTO bridge** — 2026-06-23: `TwilioVoiceBridgeProvisioner` (providers) deploys the `pstn-bridge` Serverless function into the user's own Twilio account and returns the per-account URL; `CallRoutingService.autoConfigure` now provisions-then-applies (retired the hardcoded `AUTO_BRIDGE_URL`/`managedBridgeUrl`). Bundles `providers/.../resources/twilio/pstn-bridge.js`. Injectable `Transport`/`Sleeper` seams (no Mockito in providers — hand-rolled scripted fake). 8 provisioner tests + rewritten CallRouting AUTO tests. Full build + test green.
- [x] **Leads search/filter/paging — Phase 1** — 2026-06-24: full build + test green. Domain: `LeadStatus` enum, `Cursor`, `Page<T>`, `LeadColumn`, `LeadFilter` (+`DncFilter` tri-state, builder, `all()`/`withCursor()`); `Lead` extended to 14 components (adds `tags`, `customFields`, `leadStatus`) — fixed all 6 `new Lead(...)` call sites. Storage: migration `V4__leads_custom_fields.sql` (lead_status/custom_fields cols + partial-unique live-phone index), `SqliteLeadRepository.findPage`/`customFieldKeys`/`distinctTags` (keyset WHERE-builder + JSON1 `json_each` facets), `DomainMappers` custom-fields JSON. Services: `LeadService.findPage`/`customFieldKeys`/`distinctTags`. UI: reworked Leads screen — debounced search, faceted `LeadFiltersPopover`, keyset infinite-scroll, dynamic custom-field columns, status/tags/DNC columns, count label, Add-Lead dialog. SRP carve-outs to keep `LeadsController` ≤250 lines: `LeadsPager`+`LeadFilterState` (headless, unit-tested), `LeadsPageLoader`+`LeadsTableColumns`+`AddLeadDialog`+`LeadFiltersPopover` (JavaFX view helpers, not unit-tested), `LeadStatusLabel` (headless, tested, dedupes a switch shared with the popover).

---

## Current State — 2026-06-21

### Scaffold files created
- [x] `AGENTS.md` — coding standards, architecture, conventions, schema, UI standards
- [x] `MEMORY.md` — this file
- [x] `.github/agents/buyer.agent.md` — 6 buyer personas
- [x] `.github/agents/dharmendra.agent.md` — senior Java/JavaFX architect agent
- [x] `.github/agents/va.agent.md` — 5 daily operator personas
- [x] `.github/copilot-instructions.md` — VS Code Copilot auto-load instructions
- [x] `.plan/coldcalling.md` — full planning document

### Build status — `./gradlew build`: **GREEN** (64 tests, 0 failures)

### Not yet started
- [x] Leads screen (FXML + `LeadsController`) — Phase 1 done (search/filter/keyset paging/dynamic columns). Phase 2–4 (lists rail + bulk select + inline grid; CSV import + PhoneNormalizer; quick-add) pending.
- [ ] Call history screen (`CallHistoryController`)
- [ ] SMS/Messages screen (`MessagesController`)
- [ ] Power dialer screen + engine (`PowerDialerController`, `PowerDialerService`)
- [ ] Settings screen (`SettingsController`)
- [ ] AWS CDK infra (Lambda + API Gateway + DynamoDB) — `src/infra/`
- [ ] jpackage native installers (macOS DMG, Windows MSI, Linux DEB)

---

## Key Domain Types (Planned)

```java
// Value objects
record PhoneNumber(String value)        // E.164 validated
record LeadId(long value)               // positive long
record CallId(long value)
record PhoneNumberId(long value)

// Sealed state machines
sealed interface CallState permits Idle, Ringing, Active, OnHold, Ended
sealed interface PowerDialerState permits Stopped, Running, Paused
sealed interface SmsStatus permits Pending, Delivered, Failed
sealed interface NumberReputation permits Clean, Warning, Flagged

// Domain events
sealed interface DomainEvent permits
    DomainEvent.IncomingCall,
    DomainEvent.CallAnswered,
    DomainEvent.CallEnded,
    DomainEvent.IncomingSms,
    DomainEvent.NumberReputationChanged
```

---

## SMS Relay Architecture (AWS)

```
Twilio → POST /sms-inbound (API Gateway HTTP)
       → Lambda (stores to DynamoDB, looks up WebSocket connectionId)
       → POST /connections/{connectionId} (API Gateway WebSocket)
       → Desktop (WebSocket client, connected on startup)

Desktop → POST /sms-outbound → Lambda → Twilio REST API
```

Desktop reconnects WebSocket every 60s if disconnected (API Gateway drops idle connections at 10min).

---

## Call Flow (Inbound)

```
Twilio SIP Proxy → SIP INVITE → JAIN-SIP stack (port 5060)
                 → IncomingCallListener.processRequest()
                 → Emit DomainEvent.IncomingCall
                 → Platform.runLater(() → IncomingCallController.ring())
                 → User presses Space / clicks Answer
                 → Send 200 OK with SDP (public IP from STUN)
                 → Start RTP session (jlibrtp)
                 → Audio: microphone → G.711 encode → RTP → Twilio → prospect
                 → Audio: Twilio → RTP → G.711 decode → speaker
```

## Call Flow (Outbound)

```
User enters number → DialerController
                   → CallService.dial(PhoneNumber remote, PhoneNumber local)
                   → Build SIP INVITE (From: local, To: remote, SDP: STUN public IP)
                   → JAIN-SIP sends INVITE to Twilio proxy
                   → 180 Ringing → update UI
                   → 200 OK → extract remote SDP → start RTP session
                   → Audio pipeline (same as inbound)
                   → User hangs up → send BYE → terminate RTP
```

---

## Power Dialer Engine (Planned)

```
PowerDialerSession:
  - callListId: CallListId
  - currentPosition: int
  - state: PowerDialerState
  - dialedCount: int
  - connectedCount: int
  - startedAt: Instant

Algorithm:
  1. Load leads at position >= currentPosition where status = 'pending'
  2. Dial next lead using round-robin number selection
  3. On 200 OK → pause auto-advance, wait for user disposition
  4. On busy/no-answer → write call record, advance currentPosition, schedule next dial
  5. On list end → emit PowerDialerState.Stopped, show session summary
```

---

## Pending Decisions

| Topic | Options | Status |
|---|---|---|
| Audio echo cancellation | javax.sound.sampled AEC line? Custom? | Undecided |
| Call recording format | WAV (lossless) vs MP3 (compressed) | Undecided — lean WAV for quality |
| Voicemail detection | AMD (Answering Machine Detection) via SIP? Audio analysis? | Undecided |
| Number rotation strategy | Round-robin vs least-used vs area-code-match | Implemented — round-robin across the active pool + sticky per-prospect (CallerIdSelector). Least-used / area-code-match deferred. |
| DNC list source | SQLite table (manual) vs external DNC service API | SQLite table for v1 |
| Call transcript | Local Whisper? Cloud ASR? | Deferred — not v1 |

---

## Conventions & Gotchas

- **FX thread rule**: Never call JDBC or SIP methods on the FX Application Thread. Always `CompletableFuture.runAsync` then `Platform.runLater`.
- **SQLite WAL mode**: Enable WAL mode on every new connection (`PRAGMA journal_mode=WAL`) for better read concurrency.
- **JAIN-SIP threading**: All SIP listeners run on the JAIN-SIP internal thread. Dispatch to services immediately, never do blocking work in listeners.
- **jlibrtp**: Call `RTPSession.endSession()` on every call termination — jlibrtp leaks threads if not closed.
- **jpackage on macOS**: Requires `--mac-sign` and an Apple Developer ID certificate for Gatekeeper. For dev builds, use `--type app-image` to skip signing.
- **SQLite on Windows**: sqlite-jdbc bundles native `.dll`. No separate installation needed.
- **AtlantaFX theme loading**: Call `Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet())` in `Application.init()` before any scene is shown.
- **FlywayDB classpath**: Migration SQL files must be in `storage/src/main/resources/db/migration/` for Flyway to find them on the classpath.
- **Gradle module visibility**: Use `api` vs `implementation` in `build.gradle` dependencies intentionally — `ui/` needs `api` deps on modules it exposes to `app/`.

---

## Session Log

| Date | Summary |
|------|---------|
| 2026-06-21 | Project planning session. Defined architecture (Java 21 + JavaFX + JAIN-SIP + jlibrtp + SQLite + Twilio + AWS CDK). Designed complete SQLite schema. Designed all 8 JavaFX screens (Apple HIG). Chose color tokens, typography scale, spacing system. Created all 7 scaffold files. |
| 2026-06-21 | Built domain layer (17 tests), storage layer (15 tests), telephony layer (38 tests), providers layer (28 tests), bespoke Cupertino Light CSS, UI layer (DialerController, IncomingCallController, ActiveCallController + FXML). |
| 2026-06-21 | Added `services/` module (CallService, LeadService, SmsService, PhoneNumberService — 26 tests). Rewired `ColdCallingApp`. Fixed all blocked issues: circular dependency (TelephonyService.setListener), `var` lambda syntax, `PhoneNumberId(0)` domain invariant violation, `NumberReputation`/`SmsStatus` record instantiation, JAIN-SIP compile classpath. Build: GREEN. 64 tests, 0 failures. |
| 2026-06-22 | Active-call audio UX (`.plan/active-call-audio-visualization.md`), 3 phases, build GREEN. P1: call-control icon hover/click CSS + mute-red/hold-amber tints. P2: pull-model audio levels — `AudioLevels.rms` (shared), volatile mic/remote levels on `AudioPipeline`, `TelephonyService`/`CallService` getters, new `WaveformBuffer` (6 tests) + `AudioWaveform` Canvas, controller `AnimationTimer` driving adaptive avatar halo + threshold ripple + live mic waveform + mic-on/off glyph. P3: mid-call device switch — `TelephonyService.switchAudioDevices` (volatile `activeRtp`, rebuilds pipeline keeping RTP+recorder), in-call `bi-sliders` ContextMenu (mic/speaker radio submenus), write-through to Settings via app `switchAudioDevices()` `BiConsumer`. |
| 2026-06-22 | Mobile-style call UX + meaningful motion (`.plan/mobile-call-ux-and-motion.md`), 3 phases, build GREEN. **P1 (motion + indicators):** `Motion` gate (in-app Reduce Motion setting only, `isReduced`/`setReduced`/`pressFlash`), SettingsService keys (`voicemail.greeting.path`, `appearance.reduceMotion`), REC chip + connect bloom + dial-pad press flash, queue "up next" preview wired through `PowerDialerService.upcoming()`, `isRecording` getter chain (AudioPipeline→TelephonyService→CallService). **P2 (voicemail drop):** `VoicemailGreeting.load` (validates PCM_SIGNED/8000Hz/mono/16-bit, 160-sample frames), `AudioPipeline.playGreeting`, `TelephonyService.playGreeting`, `CallService.dropVoicemail`, end-to-end UI wiring (V key, Settings greeting picker copying to `~/.coldcalling/voicemail-greeting.wav`, power-dialer finalize-and-advance on drop). **P3 (alt-tab + output):** `CallHudWindow` (transparent always-on-top pill, drag, pulse timer + REC dot, mute/hang, fade/scale in-out gated by Motion) shown via `CallHudVisibility.shouldShow(callLive, mainFocused)`; primary speaker/headset `outputButton` on the call card cycles output devices through the shipped switch path (••• menu kept as full picker). SKIP per §0/§8: AMD, slide-to-answer, max-motion. Number-reputation/Scam-Likely escalated to its own future plan. |
| 2026-06-23 | Multi-number rotation + sticky caller-ID. New `CallerIdSelector` (services): pool = active owned numbers; sticky reuse of the last **outbound** number per prospect (derived from `CallRepository.findByRemoteNumber`, no migration); otherwise round-robin via an in-memory `AtomicInteger`; empty-pool → `getDefault()` fallback. Wired into both manual dial (`ColdCallingApp.onDial`) and `PowerDialerService` (replaced its private `resolveLocal` modulo; swapped `PhoneNumberService`→`CallerIdSelector` dependency). `PhoneNumberService` gained `listAll()` + `setActive(id, active)` (toggles the `active` flag via `repo.update`). Settings **General**→**Calling numbers**: single default-number `ComboBox` replaced by a multi-select checkbox pool (`numberPoolBox` VBox; checked = active); save toggles `setActive`. Default is no longer user-set in Settings (onboarding still seeds one as fallback). Tests: `CallerIdSelectorTest` (8), `PhoneNumberServiceTest` (+5), `PowerDialerServiceTest` (mock swap). Build GREEN. |
| 2026-06-24 | Leads workbench Phases 2–4 (`.plan/leads-lists-search-filter.md`). **P2 lists + inline grid:** `CallListService` (rename/count/addLeads/removeLead, nested `ListSummary`), repo bulk ops (`bulkSoftDelete`/`bulkSetStatus`/`bulkSetDnc`, `addLeads` INSERT OR IGNORE), `Lead.withCustomField`; UI carved into lists rail, bulk bar, quick-add bar, clipboard import, `LeadColumnManager`, `LeadsTableInteractions` (headless `LeadSelectionModel`/`ClipboardRowParser`/`LeadColumnPrefs`/`LeadPhoneParser` tested). `LeadsController` 290 lines (composition-root carve-out, >250 accepted). **P3 calling-grade CSV import:** libphonenumber 8.13.50 + commons-csv 1.11.0; `PhoneNormalizer` (sealed `Outcome` Normalized/NeedsReview/Empty, 3-tier silent/assumed/review, 9 tests); V5 migration (`import_batch_id`, `import_batches`, `import_mappings`); SRP-split `LeadImportRepository` (non-destructive COALESCE upsert, never touches notes/disposition) + `ImportBatchRepository` (undo) + `ImportMappingRepository`; services `imports/` pipeline (`CsvSource`, `ColumnAutoDetector` header+content sniff, `RowResolver`, `LeadImportService` preview/commit classify VALID/NEEDS_REVIEW/DUPLICATE/DNC/EMPTY + reconciling summary, `ImportMappingService` signature templates); 4-step `ImportWizardDialog` (Map→Review→Summary, default-country, primary-phone picker, review tray) driven by headless `ImportWizardModel` (10 tests) + `LeadImportFlow` (file pick→parse). **P3.5 undo:** `LeadImportService.undo`/`recentBatches`, surfaced on wizard Summary (created-only revert; updates not reverted in v1). **P4 mid-call quick-add:** `QuickAddModel` (tested) + `QuickAddPopover` + global `Cmd/Ctrl+Shift+A` accelerator on the primary Scene, pre-targets the power-dialer's active list, never moves dialer position. App wiring: `PhoneNormalizer`+`LeadImportService` (+import repos) constructed in `ColdCallingApp`, threaded through `MainWindow.Dependencies`→`LeadsController`. Deferred (YAGNI): mapping-template auto-apply UI, dedupe skip/merge modes, XLSX, Phase 5. Build + full test suite GREEN. |

---

*This file is auto-maintained. Do not remove sections — only append or update.*