# Copilot Instructions — coldCalling

> Auto-loaded into every GitHub Copilot chat for this repository.

## Project Identity

- **Name:** coldCalling
- **Purpose:** Cross-platform cold calling desktop application — outbound/inbound SIP calls, SMS, power dialer, local presence, multi-number management.
- **Sister project:** [`sequence`](../sequence) — cold email outreach sequencer. This app handles voice and SMS; sequence handles email.
- **Stack:** Java 21 · JavaFX 21 · AtlantaFX · JAIN-SIP · jlibrtp · G.711 PCMU · SQLite (sqlite-jdbc) · FlywayDB · twilio REST + SIP · AWS CDK (TypeScript) for SMS relay
- **Build:** Gradle 8 multi-module
- **Packaging:** jpackage (macOS DMG, Windows MSI, Linux DEB/RPM)

## Before Any Work

1. Read `AGENTS.md` (root of repo) — it is the **single source of truth** for all coding standards, architecture, and conventions. Follow it strictly.
2. Read `MEMORY.md` (root of repo) — it contains accumulated project decisions, completed work, and ongoing context from past sessions.
3. Follow the layered architecture strictly: **UI → Services → Repositories → Domain → SQLite**
4. Check the `.plan/` folder at the workspace root for active implementation plans. Read any `.plan/*.md` files before starting — they contain locked product decisions.

## Key Rules (quick reference)

- Java 21 strict: records for value objects, sealed interfaces for sum types, pattern matching, no nulls in public APIs.
- No `var` when the type is not immediately obvious. No raw types. No unchecked casts.
- All public APIs must be null-safe — use `Optional<T>` or sealed `Result<T, E>` types.
- SIP signaling runs on a dedicated SIP thread. Audio runs on dedicated audio threads. JavaFX UI runs on the FX Application Thread. NEVER block the FX thread.
- Use `Platform.runLater()` to dispatch from any background thread to the FX thread.
- Audio format is fixed: G.711 PCMU, 8000 Hz, 8-bit, mono. No other codecs.
- All database access goes through repository classes. No raw JDBC in services or UI.
- FlywayDB handles all migrations. Never modify existing migration SQL files.
- SQLite file lives at `~/.coldcalling/data.db` on all platforms.
- TDD is mandatory: write tests FIRST, then implement.
- Run `./gradlew test` before marking any task complete. Zero failures required.

## Module Boundaries

```
app/         → Entry point, DI wiring, lifecycle management. No business logic.
domain/      → Records, sealed interfaces, value objects, domain events. Zero dependencies.
telephony/   → SIP (JAIN-SIP), RTP (jlibrtp), G.711 audio pipeline. Depends on domain/.
storage/     → SQLite repositories, FlywayDB migrations. Depends on domain/.
providers/   → twilio REST client, SMS WebSocket relay client. Depends on domain/.
ui/          → JavaFX controllers, FXML, bindings, AtlantaFX styles. Depends on all above.
infra/       → AWS CDK (TypeScript) — Lambda + API Gateway + DynamoDB for SMS relay. Standalone.
```

## Telephony Rules

- SIP registration: REGISTER on startup, re-register every 60 seconds.
- Inbound calls: SIP INVITE → `CallEvent.IncomingCall` → UI rings → user answers → RTP audio session.
- Outbound calls: UI dials → SIP INVITE → RTP audio session on 200 OK.
- Audio target: < 150ms end-to-end latency.
- NAT traversal: minimal STUN client to discover public IP for SDP generation.
- Power dialer: ordered contact list → auto-dial next → advance on BYE/timeout. Pause is stateful.

## SMS Relay Architecture

- twilio POSTs inbound SMS to AWS API Gateway HTTP endpoint.
- Lambda stores the message in DynamoDB, sends to the desktop via API Gateway WebSocket.
- Desktop connects as WebSocket client on startup; reconnects on disconnect.
- Outbound SMS goes directly via twilio REST API (no relay needed).

## Mandatory Post-Task Checklist

Before marking any task complete, ALL of the following must pass:

### 1. SOLID + KISS + YAGNI audit
- **S**: Does every class/method do exactly ONE thing?
- **O**: Extended via composition, not modification of working abstractions?
- **I**: Interfaces small and focused — no consumer depends on unused methods?
- **D**: High-level modules depend on domain abstractions, not concrete implementations?
- **KISS**: Simplest correct solution? No premature abstraction?
- **YAGNI**: Only what's needed right now?

### 2. Build check
```bash
./gradlew build
```
Zero compilation errors.

### 3. Tests
```bash
./gradlew test
```
- All existing tests green.
- Every new service, repository, and domain class has a co-located test.
- Domain layer ≥ 95% coverage. Services ≥ 90%. Repositories ≥ 85%.
- TDD: Red → Green → Refactor.

## Session Workflow

- **Test-first development**: Write failing tests defining expected behavior BEFORE writing implementation.
- At the end of significant work sessions, update `MEMORY.md` with decisions made, features completed, and pending context.
