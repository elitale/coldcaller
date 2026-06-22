# coldCalling — Full Planning Document

> Living plan for the coldCalling desktop application. Updated as phases complete.
> See `AGENTS.md` for all standards. See `MEMORY.md` for session log.

---

## Product Vision

A native desktop application for professional cold callers. Replaces browser-based dialers (Aircall, Kixie, Orum) with a faster, cheaper, privacy-first native client built on pure SIP. Tight integration with the `sequence` email outreach companion.

**Target users:**
- Cold calling agencies: 8–15 SDRs, 1,800–2,400 calls/day across 30–100 numbers
- Solo freelance callers: 80–100 calls/day, 3–5 numbers, simple setup
- B2B SaaS SDR teams: 50–150 calls/day, CRM-connected, call recording required

**Core value:**
- 6× cheaper than Twilio/Aircall ($0.002/min via twilio)
- No browser — pure SIP with < 150ms audio latency
- Unlimited numbers, local presence built-in
- Power dialer with automatic number rotation and DNC enforcement

---

## Module Map

```
coldcalling/src/
├── domain/        Pure domain — records, sealed interfaces, events, value objects
├── storage/       SQLite repositories, FlywayDB migrations
├── telephony/     SIP UA (JAIN-SIP), RTP (jlibrtp), G.711 audio pipeline, STUN
├── providers/     twilio REST client, SMS WebSocket relay client
├── ui/            JavaFX 21 controllers, FXML layouts, AtlantaFX bindings
├── app/           Entry point, DI wiring, lifecycle management
└── infra/         AWS CDK (TypeScript) — SMS relay Lambda + API Gateway
```

---

## Phase 0 — Project Scaffold ✅ (2026-06-21)

- [x] Architecture decision log
- [x] `AGENTS.md` — full project standards
- [x] `MEMORY.md` — persistent session memory
- [x] `.github/agents/buyer.agent.md`
- [x] `.github/agents/dharmendra.agent.md`
- [x] `.github/agents/va.agent.md`
- [x] `.github/copilot-instructions.md`
- [x] `.plan/coldcalling.md` (this file)

---

## Phase 1 — Build Scaffold

**Goal:** A compilable, empty multi-module Gradle project with correct module boundaries.

### Tasks
- [ ] `settings.gradle` — root project, includes all modules
- [ ] Root `build.gradle` — Gradle 8 conventions plugin, Java 21, shared deps versions
- [ ] `domain/build.gradle` — no external deps
- [ ] `storage/build.gradle` — sqlite-jdbc, flyway-core, depends on domain
- [ ] `telephony/build.gradle` — jain-sip-ri, jlibrtp, depends on domain
- [ ] `providers/build.gradle` — jackson, depends on domain
- [ ] `ui/build.gradle` — javafx-controls, javafx-fxml, atlantafx, depends on domain/storage/telephony/providers
- [ ] `app/build.gradle` — depends on all, jpackage config, logback
- [ ] Source directory tree for all modules (`src/main/java`, `src/test/java`, `src/main/resources`)
- [ ] Root `.gitignore` (Gradle, IntelliJ, macOS)
- [ ] `gradlew` + `gradlew.bat` bootstrap scripts
- [ ] `./gradlew build` passes (zero source, zero errors)

### Dependency Versions (locked)
| Artifact | Version |
|---|---|
| Java | 21 |
| JavaFX | 21.0.2 |
| AtlantaFX | 2.0.1 |
| JAIN-SIP RI | 1.3.0-91 |
| jlibrtp | 0.8.0 |
| sqlite-jdbc | 3.45.1.0 |
| flyway-core | 10.10.0 |
| jackson-databind | 2.17.0 |
| slf4j-api | 2.0.12 |
| logback-classic | 1.5.3 |
| junit-jupiter | 5.10.2 |
| mockito-core | 5.11.0 |
| assertj-core | 3.25.3 |

---

## Phase 2 — Domain Layer

**Goal:** All domain records and sealed interfaces. Zero external dependencies. ≥ 95% test coverage.

### Value Objects (Records)
- [ ] `PhoneNumber` — E.164 validated string wrapper
- [ ] `ContactId`, `PhoneNumberId`, `CallId`, `SmsId`, `CallListId` — positive-long ID wrappers
- [ ] `AreaCode` — 3-digit string wrapper
- [ ] `E164PhoneNumber` (alias for `PhoneNumber` if needed for clarity)

### Sealed Interfaces (Sum Types)
- [ ] `CallState` — `Idle | Ringing(caller, arrivedAt) | Active(remote, connectedAt) | OnHold(remote, heldAt) | Ended(reason, duration)`
- [ ] `PowerDialerState` — `Stopped | Running(session) | Paused(session)`
- [ ] `CallDirection` — `Inbound | Outbound`
- [ ] `CallDisposition` — `Interested | NotInterested | Callback(scheduledAt) | Voicemail | NoAnswer | Busy | DNC | Failed(reason)`
- [ ] `NumberReputation` — `Clean | Warning | Flagged`
- [ ] `SmsDirection` — `Inbound | Outbound`
- [ ] `SmsStatus` — `Pending | Delivered | Failed(reason)`
- [ ] `Result<T>` — `Ok(value) | Err(message, cause?)`
- [ ] `EndReason` — `HungUp | RemoteHungUp | Timeout | Error(message) | Rejected | Busy`

### Domain Events
- [ ] `DomainEvent` sealed interface — `IncomingCall | CallAnswered | CallEnded | IncomingSms | NumberReputationChanged`

### Domain Entities (Records)
- [ ] `Contact` — id, firstName, lastName, phone, company, title, email, tags, notes, dnc, createdAt
- [ ] `PhoneNumberRecord` — id, number, friendlyName, areaCode, reputation, dailyCalls, active
- [ ] `Call` — id, direction, phoneNumberId, contactId, remoteNumber, state, disposition, startedAt, answeredAt, endedAt, durationMs, recordingPath, notes
- [ ] `SmsMessage` — id, direction, phoneNumberId, contactId, remoteNumber, body, status, sentAt
- [ ] `CallList` — id, name, description, contacts (ordered)
- [ ] `PowerDialerSession` — id, callListId, currentPosition, state, dialedCount, connectedCount, startedAt

### Tests
- [ ] `PhoneNumberTest` — valid E.164, invalid formats, null rejection
- [ ] `CallStateTest` — transition logic, switch exhaustiveness
- [ ] `ResultTest` — Ok/Err construction, mapping
- [ ] `ContactTest` — record construction, tag parsing

---

## Phase 3 — Storage Layer

**Goal:** SQLite repositories with FlywayDB migrations. All queries tested against in-memory SQLite.

### Tasks
- [ ] `DatabaseManager` — connection pool (single connection for SQLite), WAL mode, foreign keys ON
- [ ] FlywayDB migration `V1__initial_schema.sql` — all tables from AGENTS.md §9.3
- [ ] `ContactRepository` — CRUD + search + soft-delete + DNC query
- [ ] `PhoneNumberRepository` — CRUD + active filter + daily call count update
- [ ] `CallRepository` — insert + find by contact + find by date range + disposition update
- [ ] `SmsRepository` — insert + find by contact + thread query
- [ ] `CallListRepository` — CRUD + contact membership + position reorder
- [ ] `SettingsRepository` — key-value get/put
- [ ] Tests for all repositories (in-memory SQLite, not mock)

---

## Phase 4 — Telephony Layer

**Goal:** SIP UA registration, inbound/outbound call handling, RTP audio pipeline, G.711 PCMU codec.

### STUN Client
- [ ] `StunClient` — single binding request to `stun.twilio.com:3478`, returns public IP + port
- [ ] `StunResponse` record — publicIp, publicPort
- [ ] `StunClientTest` — parse STUN response bytes, handle timeout

### SIP User Agent
- [ ] `SipStack` — JAIN-SIP stack initialization (port 5060 UDP)
- [ ] `SipRegistrar` — REGISTER on startup, refresh timer (60s), re-register on failure
- [ ] `IncomingCallListener` — process INVITE, emit `DomainEvent.IncomingCall`
- [ ] `OutboundCallManager` — build INVITE, handle 180/200/4xx/5xx responses, send BYE
- [ ] `SdpBuilder` — build SDP offer/answer with STUN public IP, G.711 PCMU only
- [ ] `SipEventDispatcher` — dispatches SIP callbacks off SIP thread via `CompletableFuture`

### RTP + Audio
- [ ] `G711Codec` — `encode(byte[] pcm) → byte[]`, `decode(byte[] pcmu) → byte[]`
- [ ] `AudioDeviceManager` — enumerate input/output via `javax.sound.sampled`
- [ ] `MicrophoneCapturer` — reads PCM from selected input device, 8000Hz/8-bit/mono
- [ ] `SpeakerPlayer` — writes PCM to selected output device
- [ ] `RtpSession` — jlibrtp session wrapper; send PCMU packets, receive + decode
- [ ] `JitterBuffer` — 20ms min, configurable up to 100ms
- [ ] `AudioPipeline` — connects mic → encoder → RTP send + RTP receive → decoder → speaker
- [ ] `SilenceDetector` — suppress RTP when mic silent > 500ms

### Tests
- [ ] `G711CodecTest` — encode/decode roundtrip, silence frames
- [ ] `StunClientTest` — parse raw STUN binding response
- [ ] `SdpBuilderTest` — SDP output contains correct codec, IP, port

---

## Phase 5 — Providers Layer

**Goal:** twilio REST client (number management + SMS outbound), SMS WebSocket relay client.

### twilio REST Client
- [ ] `twillioClient` — Java 21 `HttpClient` wrapper, base URL, API key (from OS keychain)
- [ ] `twillioNumberService` — list numbers, purchase number, release number
- [ ] `twillioSmsService` — send SMS (REST POST to twilio)
- [ ] `twillioResponse<T>` sealed interface — `Success(body) | RateLimit(retryAfter) | Error(code, message)`

### SMS WebSocket Relay Client
- [ ] `SmsRelayClient` — connects to API Gateway WebSocket URL on startup
- [ ] `SmsRelayReconnector` — reconnect every 60s if disconnected, exponential backoff on error
- [ ] `InboundSmsHandler` — parses relay JSON message → `DomainEvent.IncomingSms`

### Tests
- [ ] `twillioClientTest` — mock `HttpClient`, verify request shape + auth header
- [ ] `twillioSmsServiceTest` — POST body, E.164 formatting
- [ ] `SmsRelayClientTest` — JSON parse, reconnect logic

---

## Phase 6 — Service Layer

**Goal:** Business logic. All services take domain types in, return `Result<T>` out. No JDBC, no SIP objects.

### Services
- [ ] `CallService` — `dial()`, `answer()`, `hangup()`, `hold()`, `resume()`, `addNote()`; enforces DNC
- [ ] `ContactService` — CRUD + import CSV + DNC management + search
- [ ] `PhoneNumberService` — provision number, release, rotation algorithm
- [ ] `SmsService` — send + thread query + mark read
- [ ] `PowerDialerService` — start/pause/resume/stop session; advance algorithm; call record write
- [ ] `CallHistoryService` — filtered queries, export CSV, analytics aggregates
- [ ] `SettingsService` — read/write settings, audio device preference
- [ ] `DncService` — check + add + remove from DNC list

### Tests (all services)
- [ ] Mock repositories, mock telephony, verify business rules
- [ ] DNC enforcement: `CallService.dial()` returns `Err` if DNC = true
- [ ] Power dialer state machine: all transitions covered

---

## Phase 7 — AWS CDK Infra (SMS Relay)

**Goal:** Deploy Lambda + API Gateway + DynamoDB for inbound SMS relay.

### Stack Components
- [ ] `ColdCallingStack` (CDK TypeScript)
- [ ] `connections` DynamoDB table — `connectionId` (PK), `userId`, `ttl`
- [ ] `SmsInboundFunction` — POST /sms-inbound handler; validates twilio webhook sig; looks up connectionId; calls API GW `postToConnection`
- [ ] `ConnectFunction` — WebSocket $connect handler; stores connectionId in DynamoDB
- [ ] `DisconnectFunction` — WebSocket $disconnect handler; removes connectionId
- [ ] `SmsOutboundFunction` — (optional v1) relay outbound SMS from desktop to twilio REST
- [ ] API Gateway HTTP API — POST /sms-inbound (twilio webhook target)
- [ ] API Gateway WebSocket API — desktop client connects here
- [ ] IAM roles, Lambda env vars (via Secrets Manager), CORS config
- [ ] `cdk deploy` verified in us-east-1

---

## Phase 8 — JavaFX UI

**Goal:** All 8 screens, Apple HIG design, no FX thread blocking, loading/error/empty states on every list.

### Screens (in build order)
- [ ] `MainWindow` — sidebar navigation, stage setup, theme application (AtlantaFX)
- [ ] `DialerController` + `dialer-view.fxml` — dial pad, recent calls list, call button
- [ ] `IncomingCallController` + `incoming-call-view.fxml` — full-screen overlay, answer/reject, caller ID
- [ ] `ActiveCallController` + `active-call-view.fxml` — timer, mute, hold, hang up, notes field
- [ ] `ContactsController` + `contacts-view.fxml` — list + search + contact detail panel
- [ ] `CallHistoryController` + `call-history-view.fxml` — sortable table, filters, export
- [ ] `MessagesController` + `messages-view.fxml` — inbox list + conversation thread
- [ ] `PowerDialerController` + `power-dialer-view.fxml` — list selection, session progress, advance/pause/stop
- [ ] `SettingsController` + `settings-view.fxml` — numbers tab, audio tab, SIP tab, integrations tab

### Shared UI Components
- [ ] `PhoneNumberPicker` — dropdown of owned numbers
- [ ] `CallTimer` — live elapsed time label (updates every second, off FX thread)
- [ ] `ContactCell` — custom `ListCell<Contact>` with avatar, name, number
- [ ] `StatusBadge` — colored pill for call disposition / number reputation
- [ ] `EmptyStatePane` — reusable empty state with icon + message + CTA button
- [ ] `LoadingOverlay` — spinner overlay for async operations

### Keyboard shortcuts (all registered in `KeyboardShortcutManager`)
- [ ] Space → answer call
- [ ] Escape → hang up / reject
- [ ] Tab → power dialer advance
- [ ] V → drop voicemail
- [ ] N → open note on active call
- [ ] Cmd+D / Ctrl+D → focus dialer
- [ ] Cmd+K / Ctrl+K → focus contacts

---

## Phase 9 — Integration + Polish

- [ ] Audio device hot-swap (system device change event → restart audio pipeline)
- [ ] STUN retry on network change
- [ ] SIP re-registration recovery (detect 401 loop → re-auth)
- [ ] Call recording: write WAV file as RTP streams
- [ ] Voicemail drop: pre-record → detect no-answer → play via RTP
- [ ] Number reputation polling: background job every 6h, update reputation badge
- [ ] CSV import for contacts (drag-and-drop, mapping UI)
- [ ] Session summary modal after power dialer completes
- [ ] Notification: macOS native notification on incoming call (when app is not focused)
- [ ] Updater: check GitHub Releases on startup, prompt if newer version found

---

## Phase 10 — Packaging + Distribution

- [ ] jpackage macOS: `.app` + `.dmg`, code-signed with Apple Developer ID
- [ ] jpackage Windows: `.msi`, signed with EV cert
- [ ] jpackage Linux: `.deb` + `.rpm`
- [ ] GitHub Actions CI: build + test on push (macOS + Ubuntu runners)
- [ ] GitHub Releases: attach all installers on tag push
- [ ] First-run setup wizard: SIP credentials, default number, audio device

---

## Open Questions

| # | Question | Priority | Answer |
|---|----------|----------|--------|
| 1 | Echo cancellation implementation? | Medium | Undecided — start without, add if users report echo |
| 2 | Voicemail detection (AMD)? | Medium | Audio energy analysis v1; no ML yet |
| 3 | Call recording format? | Low | WAV (lossless) for v1 |
| 4 | Call transcription? | Low | Deferred — not in v1 scope |
| 5 | DNC enrichment (external service)? | Low | SQLite manual list for v1 |
| 6 | CRM integrations (HubSpot/Salesforce)? | Low | Deferred post-v1 |
| 7 | Analytics dashboard (beyond call history)? | Low | Deferred post-v1 |

---

*This plan is the implementation roadmap. Mark tasks complete as they ship. Never remove completed tasks — archive them.*
