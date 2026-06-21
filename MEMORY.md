# MEMORY.md — coldCalling

> Persistent project memory across coding sessions. Updated by the agent at the end of significant work. Committed to git for continuity.

---

## Project Summary

**coldCalling** is a cross-platform cold calling desktop application built with Java 21 + JavaFX 21. It handles outbound/inbound SIP calls via Telnyx, SMS (inbound via AWS relay, outbound direct), power dialing, local presence (multi-number rotation), call recording, and call analytics.

Target users: cold calling agencies (8–15 SDRs, 1,800–2,400 calls/day), solo freelance callers (80–100 calls/day), and B2B SaaS SDR teams.

Sister project: [`sequence`](../sequence) handles cold email campaigns. These two products form the coldBirds outreach suite.

---

## Architecture Decisions

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-06-21 | Java 21 + JavaFX 21 desktop app (not Electron/web) | Pure SIP + RTP stack; no browser; no WebRTC; lower latency; native installer via jpackage |
| 2026-06-21 | Telnyx as primary telephony provider | $0.002/min vs Twilio's $0.013/min; excellent SIP + REST API; webhook support; number management |
| 2026-06-21 | Pure SIP + RTP — no WebRTC | JAIN-SIP (signaling) + jlibrtp (transport) + javax.sound.sampled (audio I/O); ~150 lines total for audio pipeline |
| 2026-06-21 | G.711 PCMU as sole audio codec | ~50 lines to implement; universally supported by all SIP providers; 8000Hz/8-bit/mono; no codec negotiation needed |
| 2026-06-21 | AWS Lambda + API Gateway WebSocket for inbound SMS relay | Telnyx POSTs to HTTP endpoint → Lambda → DynamoDB stores session → WebSocket pushes to desktop; desktop reconnects on drop |
| 2026-06-21 | STUN client (~100 lines) for NAT traversal | Discovers public IP for SDP `c=` line; uses `stun.telnyx.com:3478`; retries on network change |
| 2026-06-21 | SQLite embedded DB (sqlite-jdbc) + FlywayDB migrations | No server; single file at `~/.coldcalling/data.db`; jpackage bundles everything |
| 2026-06-21 | Manual constructor injection (no Spring, no Guice) | Desktop app — Spring boot-time is too heavy; CDI is overkill; wiring in `app/` module is ~200 lines |
| 2026-06-21 | Gradle 8 multi-module: domain/storage/telephony/providers/ui/app/infra | Enforces layer boundaries; `domain/` has zero external deps; `infra/` is standalone TypeScript |
| 2026-06-21 | AtlantaFX as JavaFX theming framework | Best Apple HIG implementation for JavaFX; light/dark/system auto; Inter font as SF Pro equivalent |
| 2026-06-21 | Inter font bundled in resources | Open-source visual equivalent to SF Pro; consistent rendering on all platforms |
| 2026-06-21 | OS keychain for secrets (macOS Keychain / Windows DPAPI / Linux libsecret) | Telnyx API keys and SIP credentials never touch SQLite plaintext |
| 2026-06-21 | SIP REGISTER on startup with 60s refresh | Telnyx routes inbound calls to the registered desktop SIP UA |
| 2026-06-21 | 8pt grid system + Apple HIG corner radii | 8px (cards), 6px (buttons), 4px (inputs); all padding multiples of 4px |

---

## Completed Work

- [x] All scaffold/planning files: `AGENTS.md`, `MEMORY.md`, `.plan/coldcalling.md`, agent files, copilot instructions
- [x] Gradle multi-module scaffold: `settings.gradle`, root `build.gradle`, `gradle/libs.versions.toml`, module `build.gradle` files
- [x] **Domain layer** — 17 tests PASS: all value objects (PhoneNumber, ContactId, PhoneNumberId, SmsId, AreaCode, CallDirection, CallDisposition, NumberReputation, SmsStatus, Result), sealed interfaces (CallState, PowerDialerState, DomainEvent), entity records (Contact, OwnedNumber, Call, SmsMessage, CallList, CallListContact, PowerDialerSession)
- [x] **Storage layer** — 15 tests PASS: `DatabaseManager` (SQLite + Flyway), `V1__initial_schema.sql`, all 5 Sqlite repositories (Contact, PhoneNumber, Call, Sms, Settings, CallList)
- [x] **Telephony layer** — 38 tests PASS: `G711Codec`, `StunMessage`, `StunClient`, `SipCredentials`, `SdpBuilder`, `SipEngine`, `SipRegistrar`, `RtpSession`, `AudioPipeline`, `TelephonyService`
- [x] **Providers layer** — 28 tests PASS: `TelnyxClient`, `TelnyxConfig`, `HttpSender`, `TelnyxNumberData`, `SmsRelayClient`, `SmsRelayConfig`
- [x] **Services layer** — 26 tests PASS: `CallService`, `ContactService`, `SmsService`, `PhoneNumberService`
- [x] **UI layer** — `./gradlew build` green: `MainWindow`, `DialerController`, `IncomingCallController`, `ActiveCallController`, bespoke Cupertino Light CSS (~550 lines)
- [x] **`app/` module** — `ColdCallingApp` fully wired: DB → repos → providers → services → telephony → UI

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
- [ ] Contacts screen (FXML + `ContactsController`)
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
record ContactId(long value)            // positive long
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
Telnyx → POST /sms-inbound (API Gateway HTTP)
       → Lambda (stores to DynamoDB, looks up WebSocket connectionId)
       → POST /connections/{connectionId} (API Gateway WebSocket)
       → Desktop (WebSocket client, connected on startup)

Desktop → POST /sms-outbound → Lambda → Telnyx REST API
```

Desktop reconnects WebSocket every 60s if disconnected (API Gateway drops idle connections at 10min).

---

## Call Flow (Inbound)

```
Telnyx SIP Proxy → SIP INVITE → JAIN-SIP stack (port 5060)
                 → IncomingCallListener.processRequest()
                 → Emit DomainEvent.IncomingCall
                 → Platform.runLater(() → IncomingCallController.ring())
                 → User presses Space / clicks Answer
                 → Send 200 OK with SDP (public IP from STUN)
                 → Start RTP session (jlibrtp)
                 → Audio: microphone → G.711 encode → RTP → Telnyx → prospect
                 → Audio: Telnyx → RTP → G.711 decode → speaker
```

## Call Flow (Outbound)

```
User enters number → DialerController
                   → CallService.dial(PhoneNumber remote, PhoneNumber local)
                   → Build SIP INVITE (From: local, To: remote, SDP: STUN public IP)
                   → JAIN-SIP sends INVITE to Telnyx proxy
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
  1. Load contacts at position >= currentPosition where status = 'pending'
  2. Dial next contact using round-robin number selection
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
| Number rotation strategy | Round-robin vs least-used vs area-code-match | Round-robin first, enhance later |
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
| 2026-06-21 | Project planning session. Defined architecture (Java 21 + JavaFX + JAIN-SIP + jlibrtp + SQLite + Telnyx + AWS CDK). Designed complete SQLite schema. Designed all 8 JavaFX screens (Apple HIG). Chose color tokens, typography scale, spacing system. Created all 7 scaffold files. |
| 2026-06-21 | Built domain layer (17 tests), storage layer (15 tests), telephony layer (38 tests), providers layer (28 tests), bespoke Cupertino Light CSS, UI layer (DialerController, IncomingCallController, ActiveCallController + FXML). |
| 2026-06-21 | Added `services/` module (CallService, ContactService, SmsService, PhoneNumberService — 26 tests). Rewired `ColdCallingApp`. Fixed all blocked issues: circular dependency (TelephonyService.setListener), `var` lambda syntax, `PhoneNumberId(0)` domain invariant violation, `NumberReputation`/`SmsStatus` record instantiation, JAIN-SIP compile classpath. Build: GREEN. 64 tests, 0 failures. |

---

*This file is auto-maintained. Do not remove sections — only append or update.*
