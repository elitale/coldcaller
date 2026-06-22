# AGENTS.md — coldCalling

> Single source of truth for all development standards, architecture decisions, and conventions in this project. **All agents and developers MUST follow these rules. No exceptions.**

---

## 1. Project Overview

- **Name:** coldCalling
- **Purpose:** Cross-platform cold calling desktop application for outbound/inbound SIP calls, SMS, power dialing, multi-number management, and call analytics.
- **Sister project:** [`sequence`](../sequence) — cold email outreach sequencer.
- **Type:** Native desktop application (cross-platform: Windows, macOS, Linux)
- **Language:** Java 21 (strict mode — records, sealed interfaces, pattern matching, text blocks, virtual threads)
- **UI:** JavaFX 21 + AtlantaFX (Apple HIG design system, light + dark mode, system auto)
- **Telephony:** Twilio REST + SIP (primary), Twilio (fallback). Pure SIP + RTP — no WebRTC, no browser.
- **Database:** SQLite via sqlite-jdbc + FlywayDB migrations
- **Build:** Gradle 8 multi-module
- **Packaging:** jpackage (macOS DMG, Windows MSI, Linux DEB/RPM)

### 1.1 Core Domain Concepts

| Entity | Description |
|---|---|
| **PhoneNumber** | E.164 formatted number owned by the user (purchased via Twilio). Has area code, reputation status, daily usage. |
| **Contact** | A person to call or SMS. Has name, phone, company, tags, call history. |
| **CallList** | An ordered collection of contacts used by the power dialer. |
| **Call** | A single call event — direction (in/out), number used, contact, duration, disposition, recording. |
| **SMS** | An inbound or outbound SMS message, tied to a PhoneNumber and Contact. |
| **PowerDialerSession** | A running power dialer job: current position in list, call count, connect count, state. |
| **Settings** | User preferences: default number, audio device, voicemail recordings, SIP credentials. |

---

## 2. Engineering Principles

### 2.1 SOLID — Strictly Enforced

| Principle | Rule |
|---|---|
| **S — Single Responsibility** | Every class, method, and module does ONE thing. No god classes. |
| **O — Open/Closed** | Extend via composition and interfaces — never modify working abstractions. |
| **L — Liskov Substitution** | Subtypes must be fully interchangeable with their base contract. |
| **I — Interface Segregation** | Small, focused interfaces. No consumer depends on methods it doesn't use. |
| **D — Dependency Inversion** | High-level modules depend on domain abstractions, not concrete implementations. |

### 2.2 Additional Principles

- **DRY** — Extract shared behavior. Keep distinct shape inline.
- **KISS** — Simplest correct solution. No premature abstraction.
- **YAGNI** — Build only what is needed right now.
- **Fail Fast** — Validate at boundaries. Throw with clear messages. Never silently swallow errors.
- **Composition over Inheritance** — Always.
- **Thread Safety** — SIP thread, audio thread, FX thread are separate. Every cross-thread call is explicit.

---

## 3. Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (records, sealed interfaces, pattern matching, text blocks, virtual threads) |
| UI | JavaFX 21 + AtlantaFX + FXML |
| Telephony (SIP) | JAIN-SIP 1.3 |
| Telephony (RTP/Audio) | jlibrtp + javax.sound.sampled |
| Audio Codec | G.711 PCMU (8000 Hz, 8-bit, mono) |
| NAT Traversal | Custom STUN client (~100 lines) |
| Telephony Provider | Twilio REST API + SIP registration |
| SMS Relay (inbound) | AWS API Gateway WebSocket + Lambda + DynamoDB |
| Database | SQLite via sqlite-jdbc |
| Migrations | FlywayDB |
| HTTP Client | Java 21 `HttpClient` (built-in) |
| JSON | Jackson 2 (databind + datatype-jdk8) |
| Logging | SLF4J + Logback |
| DI | Manual constructor injection (no Spring — desktop app) |
| Build | Gradle 8 multi-module |
| Packaging | jpackage (JDK built-in) |
| Infrastructure (IaC) | AWS CDK (TypeScript) |
| Tests | JUnit 5 + Mockito 5 + AssertJ |

---

## 4. Module Structure

```
coldcalling/src/                  # Root Gradle project
├── app/                      # Application entry point, DI wiring, lifecycle
├── domain/                   # Pure domain — records, sealed interfaces, value objects, domain events
├── telephony/                # SIP (JAIN-SIP), RTP (jlibrtp), G.711 audio pipeline
├── storage/                  # SQLite repositories, FlywayDB migrations
├── providers/                # Twilio REST client, SMS WebSocket relay client
├── ui/                       # JavaFX controllers, FXML, AtlantaFX bindings
└── infra/                    # AWS CDK (TypeScript) — standalone; not on the Java classpath
```

### 4.1 Module Dependency Rules

```
domain/    → no dependencies (pure Java records + sealed interfaces)
storage/   → domain/
telephony/ → domain/
providers/ → domain/
ui/        → domain/, storage/, telephony/, providers/
app/       → all modules (wiring only, no business logic)
infra/     → standalone TypeScript project
```

**Never violate these rules.** `domain/` must have zero external dependencies.

### 4.2 Package Naming

```
com.elitale.coldbirds.coldcalling.domain.*       # Domain layer
com.elitale.coldbirds.coldcalling.storage.*      # Repository layer
com.elitale.coldbirds.coldcalling.telephony.*    # Telephony layer
com.elitale.coldbirds.coldcalling.providers.*    # External provider clients
com.elitale.coldbirds.coldcalling.ui.*           # JavaFX UI
com.elitale.coldbirds.coldcalling.app.*          # Application wiring
```

---

## 5. Java 21 Coding Standards

### 5.1 Core Rules

- **No nulls in public APIs.** Return `Optional<T>` for nullable single values. Use sealed `Result<T, E>` for operations that can fail. Never return `null` from a public method.
- **No raw types.** `List<String>` not `List`. `Optional<Contact>` not `Optional`.
- **No unchecked casts** without a comment explaining why it is safe.
- **No `var`** when the inferred type is not immediately obvious at the call site.
- **Records for all value objects.** Records are immutable by default — prefer them for domain data.
- **Sealed interfaces for sum types.** Every state machine, result type, or discriminated union must be a sealed interface with exhaustive pattern matching.
- **`switch` expressions are exhaustive** — the compiler must verify every case is covered.
- **No `instanceof` checks** when a sealed interface + pattern matching can be used instead.
- **All collections returned from public methods are unmodifiable** — use `List.of()`, `List.copyOf()`, `Collections.unmodifiableList()`.
- **`final` on every field** that is not intentionally mutable.
- **Explicit visibility** on every class member — never rely on package-private defaults for public-facing APIs.

### 5.2 Value Objects (Records)

```java
// Always validate in compact constructor
public record PhoneNumber(String value) {
    public PhoneNumber {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.matches("\\+[1-9]\\d{1,14}")) {
            throw new IllegalArgumentException("Invalid E.164: " + value);
        }
    }
}

public record ContactId(long value) {
    public ContactId {
        if (value <= 0) throw new IllegalArgumentException("ContactId must be positive");
    }
}
```

### 5.3 Sum Types (Sealed Interfaces)

```java
// All state machines as sealed interfaces
public sealed interface CallState permits
        CallState.Idle,
        CallState.Ringing,
        CallState.Active,
        CallState.OnHold,
        CallState.Ended {

    record Idle() implements CallState {}
    record Ringing(PhoneNumber caller, Instant arrivedAt) implements CallState {}
    record Active(PhoneNumber remote, Instant connectedAt) implements CallState {}
    record OnHold(PhoneNumber remote, Instant heldAt) implements CallState {}
    record Ended(EndReason reason, Duration duration) implements CallState {}
}

// Always switch exhaustively
String label = switch (state) {
    case CallState.Idle ignored -> "Ready";
    case CallState.Ringing r   -> "Incoming: " + r.caller().value();
    case CallState.Active a    -> "Active";
    case CallState.OnHold h    -> "On Hold";
    case CallState.Ended e     -> "Ended";
};
```

### 5.4 Result Types

```java
// Prefer sealed Result over checked exceptions for service-layer operations
public sealed interface Result<T> permits Result.Ok, Result.Err {
    record Ok<T>(T value) implements Result<T> {}
    record Err<T>(String message, @Nullable Throwable cause) implements Result<T> {}

    static <T> Result<T> ok(T value) { return new Ok<>(value); }
    static <T> Result<T> err(String message) { return new Err<>(message, null); }
    static <T> Result<T> err(String message, Throwable cause) { return new Err<>(message, cause); }
}
```

### 5.5 Naming Conventions

| Item | Convention | Example |
|---|---|---|
| Files | `PascalCase.java` | `CallRepository.java` |
| Classes / Records / Interfaces | `PascalCase` | `PhoneNumber`, `CallState` |
| Methods / Variables | `camelCase` | `findContactById`, `callDuration` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_CONCURRENT_CALLS` |
| Packages | `lowercase.dotted` | `com.elitale.coldbirds.coldcalling.domain` |
| FXML files | `kebab-case.fxml` | `dialer-view.fxml` |
| CSS/style files | `kebab-case.css` | `dark-theme.css` |
| Test files | `PascalCaseTest.java` | `CallRepositoryTest.java` |

### 5.6 File Size Limits

- **Domain classes (records/interfaces):** Max 100 lines.
- **Services:** Max 200 lines. Split by sub-domain if larger.
- **Repositories:** Max 200 lines.
- **UI Controllers:** Max 250 lines. Extract helper classes if larger.
- **Utility classes:** Max 100 lines.

---

## 6. Architecture Layers

```
┌─────────────────────────────────────────┐
│            UI Layer (JavaFX)            │
│  Controllers · FXML · Bindings          │
├─────────────────────────────────────────┤
│         Service Layer (Business)        │
│  Call management · Power dialer         │
│  Contact management · SMS handling      │
├─────────────────────────────────────────┤
│    Telephony Layer (SIP/RTP/Audio)      │
│  SIP UA · RTP session · G.711 codec     │
├─────────────────────────────────────────┤
│    Repository Layer (Data Access)       │
│  SQLite queries · DAO pattern           │
├─────────────────────────────────────────┤
│         Domain Layer (Pure)             │
│  Records · Sealed interfaces · Events   │
├─────────────────────────────────────────┤
│         SQLite (Embedded DB)            │
└─────────────────────────────────────────┘
```

### 6.1 Layer Rules

1. **UI → Services only.** Controllers never call repositories, telephony, or providers directly.
2. **Services → Repositories and Telephony.** Services orchestrate; they do not own JDBC or SIP objects.
3. **Repositories → SQLite only.** No business logic inside repositories.
4. **Telephony → Domain only.** The telephony layer depends on domain types, not storage or UI.
5. **Domain → nothing.** Zero external dependencies. Zero.
6. **No circular dependencies** across modules.
7. **No Swing imports.** JavaFX only.

---

## 7. Threading Model

**Three distinct threads. They must never block each other.**

| Thread | Purpose | Rules |
|---|---|---|
| **FX Application Thread** | All JavaFX UI rendering and event handling | Never block. No I/O. No SIP calls. Never run business logic directly. |
| **SIP Thread** | JAIN-SIP stack processing | All SIP callbacks arrive here. Dispatch to services via `CompletableFuture`. |
| **Audio Thread(s)** | RTP send/receive, G.711 encode/decode, javax.sound.sampled | Never block. No UI calls. Use `Platform.runLater()` to update UI. |

### 7.1 Cross-Thread Rules

```java
// ✅ Correct — dispatch from SIP/audio thread to FX thread
Platform.runLater(() -> controller.updateCallState(newState));

// ✅ Correct — run blocking I/O off FX thread
CompletableFuture.runAsync(() -> contactService.importCsv(file))
    .thenRunAsync(() -> Platform.runLater(this::refreshTable));

// ❌ Wrong — blocking the FX thread
// Never do this inside a JavaFX event handler:
Thread.sleep(1000);
contactRepository.findAll(); // JDBC blocks
sipStack.sendRequest(request); // network blocks
```

### 7.2 JavaFX Observable Properties

- Use `SimpleStringProperty`, `SimpleObjectProperty<T>`, `SimpleBooleanProperty` for bindable state.
- Bind controller properties to domain state — never manually update UI fields when a binding can do it.
- Use `Platform.runLater()` to set property values from non-FX threads.

---

## 8. Telephony Standards

### 8.1 SIP

- **SIP UA (User Agent):** JAIN-SIP stack, registered to Twilio SIP proxy on startup.
- **Registration:** REGISTER on startup, refresh every 60 seconds (`Expires: 60`).
- **Inbound calls:** SIP INVITE arrives → parse → emit `DomainEvent.IncomingCall` → ring the UI.
- **Outbound calls:** UI initiates → service builds INVITE → SIP stack sends → handle 200 OK / 4xx / timeout.
- **Termination:** BYE or CANCEL terminates the call. Always send BYE on user hangup.
- **Error handling:** Every SIP response code must map to a human-readable `CallError` sealed interface.

### 8.2 RTP + Audio

- **Codec:** G.711 PCMU only. 8000 Hz, 8-bit, mono. ~50 lines to implement.
- **RTP:** jlibrtp manages packet send/receive. Audio pipeline: microphone → PCM → PCMU encode → RTP send; RTP receive → PCMU decode → PCM → speaker.
- **Jitter buffer:** 20ms minimum. Configurable up to 100ms via settings.
- **Audio devices:** Enumerate via `javax.sound.sampled.AudioSystem`. User selects input/output in settings. Default to system default.
- **Latency target:** < 150ms end-to-end.
- **Silence detection:** Suppress RTP transmission when microphone is silent (> 500ms silence).

### 8.3 NAT Traversal

- STUN client sends binding request to `stun.twilio.com:3478` on startup.
- Result (public IP + port) is used as the `c=` line in SDP offer/answer.
- Retry STUN if the network changes (detect via OS network change event).

### 8.4 Power Dialer Engine

```
State:    STOPPED → RUNNING → PAUSED → RUNNING → STOPPED
Trigger:  user start → user pause → user resume → list exhausted / user stop

Algorithm per call:
1. Get next contact from ordered CallList (by position index).
2. Dial using configured rotation number (round-robin across assigned numbers).
3. Wait for SIP response:
   - 200 OK + RTP → call is ACTIVE. Pause auto-advance. User must manually advance or dispose.
   - 486/603 Busy / 480 Unavailable → mark BUSY, auto-advance after 1s.
   - No answer timeout (30s) → drop voicemail if configured, mark NO_ANSWER, auto-advance.
   - 4xx/5xx error → mark FAILED, log error, auto-advance.
4. On advance: write call record to SQLite, proceed to step 1.
5. On list exhaustion: emit STOPPED event, show summary.
```

---

## 9. Database Standards

### 9.1 SQLite File Location

```
~/.coldcalling/data.db          # macOS + Linux
%APPDATA%\coldcalling\data.db   # Windows
```

Use `System.getProperty("os.name")` to resolve the correct path.

### 9.2 Schema Conventions

- Every table has `id INTEGER PRIMARY KEY AUTOINCREMENT`.
- Every table has `created_at INTEGER NOT NULL` (Unix epoch milliseconds).
- Every table has `updated_at INTEGER NOT NULL`.
- Use `TEXT` for strings, `INTEGER` for booleans (0/1), `INTEGER` for timestamps (epoch ms), `REAL` for decimals.
- Foreign keys are always enabled: `PRAGMA foreign_keys = ON` on every connection.
- Soft deletes: `deleted_at INTEGER` on contacts and call lists. Filter at repository layer.

### 9.3 Complete Schema

```sql
-- Phone numbers owned by the user
CREATE TABLE phone_numbers (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    number          TEXT NOT NULL UNIQUE,       -- E.164 format
    friendly_name   TEXT,
    area_code       TEXT NOT NULL,
    provider        TEXT NOT NULL DEFAULT 'twilio',
    reputation      TEXT NOT NULL DEFAULT 'clean', -- clean | warning | flagged
    daily_calls     INTEGER NOT NULL DEFAULT 0,
    active          INTEGER NOT NULL DEFAULT 1,  -- 0/1
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

-- Contacts
CREATE TABLE contacts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name      TEXT,
    last_name       TEXT,
    phone           TEXT NOT NULL,              -- E.164
    company         TEXT,
    title           TEXT,
    email           TEXT,
    tags            TEXT,                       -- JSON array of strings
    notes           TEXT,
    dnc             INTEGER NOT NULL DEFAULT 0, -- 0/1 — do not call
    deleted_at      INTEGER,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);
CREATE INDEX idx_contacts_phone ON contacts(phone);
CREATE INDEX idx_contacts_deleted_at ON contacts(deleted_at);

-- Call lists (power dialer input)
CREATE TABLE call_lists (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    description     TEXT,
    deleted_at      INTEGER,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

-- Call list membership (ordered)
CREATE TABLE call_list_contacts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    list_id         INTEGER NOT NULL REFERENCES call_lists(id) ON DELETE CASCADE,
    contact_id      INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,           -- sort order in dialer
    status          TEXT NOT NULL DEFAULT 'pending', -- pending | dialed | skipped
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    UNIQUE(list_id, contact_id)
);
CREATE INDEX idx_call_list_contacts_list ON call_list_contacts(list_id, position);

-- Call records
CREATE TABLE calls (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    direction       TEXT NOT NULL,              -- inbound | outbound
    phone_number_id INTEGER NOT NULL REFERENCES phone_numbers(id),
    contact_id      INTEGER REFERENCES contacts(id),
    remote_number   TEXT NOT NULL,              -- E.164
    status          TEXT NOT NULL,              -- ringing | active | ended | missed | failed
    disposition     TEXT,                       -- interested | not_interested | callback | voicemail | no_answer | busy | dnc
    started_at      INTEGER NOT NULL,
    answered_at     INTEGER,
    ended_at        INTEGER,
    duration_ms     INTEGER,
    recording_path  TEXT,
    notes           TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);
CREATE INDEX idx_calls_contact ON calls(contact_id);
CREATE INDEX idx_calls_started_at ON calls(started_at);
CREATE INDEX idx_calls_phone_number ON calls(phone_number_id);

-- SMS messages
CREATE TABLE sms_messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    direction       TEXT NOT NULL,              -- inbound | outbound
    phone_number_id INTEGER NOT NULL REFERENCES phone_numbers(id),
    contact_id      INTEGER REFERENCES contacts(id),
    remote_number   TEXT NOT NULL,              -- E.164
    body            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'delivered', -- delivered | failed | pending
    sent_at         INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);
CREATE INDEX idx_sms_contact ON sms_messages(contact_id);
CREATE INDEX idx_sms_sent_at ON sms_messages(sent_at);

-- Settings (key-value)
CREATE TABLE settings (
    key             TEXT PRIMARY KEY,
    value           TEXT NOT NULL,
    updated_at      INTEGER NOT NULL
);
```

### 9.4 FlywayDB Migrations

- All migration files in `storage/src/main/resources/db/migration/`.
- Naming: `V{N}__{description}.sql` — e.g. `V1__initial_schema.sql`.
- Never modify an applied migration file. Create a new one.
- Always test migrations on a fresh database before committing.

---

## 10. UI Standards (JavaFX + AtlantaFX)

### 10.1 Design System

- **Framework:** AtlantaFX (built on JavaFX 21).
- **Theme:** System auto (light/dark). User can override in settings.
- **Font:** Inter (bundled). Mirrors SF Pro. Loaded as a JavaFX font family.
- **Spacing:** 8pt grid system. All padding/gaps must be multiples of 4 (4, 8, 12, 16, 24, 32...).
- **Corner radius:** 8px for cards, 6px for buttons, 4px for inputs.
- **Icons:** Use SVG paths or JavaFX font icons. No external icon library dependency.

### 10.2 Color Tokens

```css
/* Light mode */
--color-bg-primary: #FFFFFF;
--color-bg-secondary: #F5F5F7;
--color-bg-elevated: #FFFFFF;
--color-text-primary: #1D1D1F;
--color-text-secondary: #6E6E73;
--color-accent: #0071E3;
--color-accent-hover: #0077ED;
--color-success: #34C759;
--color-warning: #FF9F0A;
--color-error: #FF3B30;

/* Dark mode */
--color-bg-primary-dark: #000000;
--color-bg-secondary-dark: #1C1C1E;
--color-bg-elevated-dark: #2C2C2E;
--color-text-primary-dark: #F5F5F7;
--color-text-secondary-dark: #98989D;
```

### 10.3 Typography Scale

| Token | Size | Weight | Use |
|---|---|---|---|
| `--type-display` | 28px | 700 | Screen titles |
| `--type-title-1` | 22px | 600 | Section headings |
| `--type-title-2` | 17px | 600 | Card titles |
| `--type-body` | 15px | 400 | Body text |
| `--type-label` | 13px | 500 | Labels, tabs |
| `--type-caption` | 12px | 400 | Secondary info |
| `--type-mono` | 13px | 400 | Phone numbers |

### 10.4 Screen Inventory

| Screen | Route / Controller | Purpose |
|---|---|---|
| Dialer | `DialerController` | Manual dial pad + recent calls |
| Incoming Call | `IncomingCallController` | Full-screen ring overlay |
| Active Call | `ActiveCallController` | Live call controls + notes |
| Contacts | `ContactsController` | Contact list + search + detail |
| Call History | `CallHistoryController` | Full call log with filters |
| Messages | `MessagesController` | SMS inbox + conversation thread |
| Power Dialer | `PowerDialerController` | List selection + session control |
| Settings | `SettingsController` | Numbers, audio, SIP, integrations |

### 10.5 UI Rules

- **Never block the FX Application Thread.** All I/O, SIP operations, and database queries must run off-thread.
- **Loading states:** Every async operation must show a progress indicator.
- **Error states:** Every error must show a human-readable message with a recovery action.
- **Empty states:** Every list/table has an empty state with a clear call-to-action.
- **Keyboard shortcuts:** Common operations must have keyboard shortcuts (see §10.6).

### 10.6 Keyboard Shortcuts

| Action | Shortcut (macOS) | Shortcut (Windows/Linux) |
|---|---|---|
| Answer incoming call | `Space` | `Space` |
| Hang up / reject | `Escape` | `Escape` |
| Power dialer advance | `Tab` | `Tab` |
| Drop voicemail | `V` (during no-answer) | `V` |
| Add call note | `N` | `N` |
| Open dialer | `Cmd+D` | `Ctrl+D` |
| Open contacts | `Cmd+K` | `Ctrl+K` |

---

## 11. Testing Standards

### 11.1 TDD Cycle

**Red → Green → Refactor.** No exceptions.

1. **Red** — write failing tests defining expected behavior, inputs, outputs, edge cases.
2. **Green** — minimum implementation to pass.
3. **Refactor** — clean up while keeping tests green.

### 11.2 Naming and Location

- Always `PascalCaseTest.java` co-located with source.
- Test package mirrors source package.

### 11.3 Coverage Targets

| Layer | Minimum |
|---|---|
| Domain (records, sealed interfaces) | ≥ 95% |
| Services | ≥ 90% |
| Repositories | ≥ 85% |
| Telephony | ≥ 80% |
| Providers | ≥ 80% |
| UI Controllers | ≥ 60% |

### 11.4 What to Test Per Layer

| Layer | What to Test | What to Mock |
|---|---|---|
| Domain | Record construction, validation, sealed interface exhaustiveness | Nothing |
| Repository | Query correctness, soft-delete filters, constraint violations | SQLite (in-memory or temp file) |
| Service | Business logic, state transitions, error handling | Repositories, Telephony, Providers |
| Telephony | G.711 encode/decode, SDP generation, STUN parsing | JAIN-SIP stack, jlibrtp |
| Provider | Twilio REST request/response mapping | Java `HttpClient` (mock) |

### 11.5 Running Tests

```bash
./gradlew test                             # Full suite
./gradlew :domain:test                     # Single module
./gradlew test --tests "*.CallStateTest"   # Single test class
./gradlew test --info                      # Verbose output
```

---

## 12. Security

- Never store Twilio API keys or SIP credentials in SQLite in plaintext. Use OS keychain:
  - macOS: `java.security.KeyStore` (Keychain)
  - Windows: `DPAPI` via JNA
  - Linux: `libsecret` via JNA or encrypted settings file
- Never log SIP passwords, API keys, or phone numbers at DEBUG level.
- DNC list must be checked before every outbound dial — service-layer enforcement, not UI-layer.
- Recording files are stored in `~/.coldcalling/src/recordings/`. Never transmitted to any server unless user explicitly enables cloud backup.
- AWS Lambda receives raw Twilio webhooks. Never log full webhook bodies (may contain PII).

---

## 13. Git & Workflow

- **Commits:** Conventional Commits — `feat:`, `fix:`, `refactor:`, `chore:`, `docs:`.
- **Branches:** `feat/description`, `fix/description`.
- No commented-out code in commits.
- No `System.out.println` in production code — use SLF4J logger.
- No `TODO` without a linked issue.

---

## 14. Pre-Implementation Checklist

Before writing any feature code, the agent MUST:

1. [ ] Read this `AGENTS.md` fully.
2. [ ] Read `MEMORY.md` for current project state.
3. [ ] Read the relevant `.plan/*.md` file if one exists.
4. [ ] Understand the feature requirements. Clarify ambiguities before starting.
5. [ ] Identify affected modules and layers.
6. [ ] Plan domain types (records + sealed interfaces) first.
7. [ ] Plan SQLite schema changes (if any). Write the migration SQL.
8. [ ] **Write tests FIRST** (TDD): define expected behavior, inputs, outputs.
9. [ ] Implement bottom-up: Domain → Repository → Service → Telephony → UI.
10. [ ] `./gradlew build` — zero errors.
11. [ ] `./gradlew test` — all green, coverage targets met.

---

## 15. Do NOT

- ❌ Return `null` from a public method.
- ❌ Use `var` when the type is non-obvious.
- ❌ Use mutable collections as return types.
- ❌ Put business logic in UI controllers.
- ❌ Put JDBC queries in services.
- ❌ Use `Thread.sleep()` on the FX Application Thread.
- ❌ Use `instanceof` when a sealed interface switch works.
- ❌ Modify applied FlywayDB migration files.
- ❌ Store secrets in SQLite in plaintext.
- ❌ Use `System.out.println` — use SLF4J.
- ❌ Add Swing imports.
- ❌ Use WebRTC or any browser-based audio.
- ❌ Use `@ts-ignore` in the CDK infra code.
- ❌ Hard-code file paths — always resolve from user home or `os.name`.
- ❌ Write production code without tests.

---

## 16. Do

- ✅ Records for all value objects with compact constructor validation.
- ✅ Sealed interfaces for all sum types.
- ✅ Exhaustive `switch` expressions.
- ✅ `Optional<T>` for nullable returns.
- ✅ `Result<T, E>` for failable operations.
- ✅ `Platform.runLater()` for all FX thread dispatches.
- ✅ `CompletableFuture` for all async work.
- ✅ `PRAGMA foreign_keys = ON` on every SQLite connection.
- ✅ FlywayDB for all schema changes.
- ✅ DNC check before every outbound dial.
- ✅ STUN on startup for NAT discovery.
- ✅ SIP REGISTER on startup with 60s refresh.
- ✅ G.711 PCMU as the only audio codec.
- ✅ Write tests first (TDD). Red → Green → Refactor.
- ✅ `./gradlew test` before marking any task complete.

---

*This file is the contract. Update it deliberately. Append, don't overwrite.*
