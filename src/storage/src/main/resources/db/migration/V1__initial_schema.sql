-- V1__initial_schema.sql
-- coldCalling initial database schema
-- FlywayDB migration — never modify this file once applied.
-- Note: PRAGMA journal_mode=WAL and PRAGMA foreign_keys=ON are set per-connection
-- in DatabaseManager, NOT here, because Flyway forbids non-transactional PRAGMA
-- statements mixed with DDL in the same migration script.

-- ============================================================
-- Phone numbers owned by the user (purchased via Telnyx)
-- ============================================================
CREATE TABLE phone_numbers (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    number          TEXT    NOT NULL UNIQUE,          -- E.164 format e.g. +14155552671
    friendly_name   TEXT,
    area_code       TEXT    NOT NULL,                 -- 3-digit string
    provider        TEXT    NOT NULL DEFAULT 'telnyx',
    reputation      TEXT    NOT NULL DEFAULT 'clean', -- clean | warning | flagged
    daily_calls     INTEGER NOT NULL DEFAULT 0,
    active          INTEGER NOT NULL DEFAULT 1,       -- 0 = inactive, 1 = active
    created_at      INTEGER NOT NULL,                 -- Unix epoch milliseconds
    updated_at      INTEGER NOT NULL
);

-- ============================================================
-- Contacts — people to call or SMS
-- ============================================================
CREATE TABLE contacts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name      TEXT,
    last_name       TEXT,
    phone           TEXT    NOT NULL,                 -- E.164
    company         TEXT,
    title           TEXT,
    email           TEXT,
    tags            TEXT,                             -- JSON array: ["tag1","tag2"]
    notes           TEXT,
    dnc             INTEGER NOT NULL DEFAULT 0,       -- 1 = do not call
    deleted_at      INTEGER,                          -- soft delete
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX idx_contacts_phone      ON contacts(phone);
CREATE INDEX idx_contacts_deleted_at ON contacts(deleted_at);
CREATE INDEX idx_contacts_dnc        ON contacts(dnc);

-- ============================================================
-- Call lists — ordered contact collections for the power dialer
-- ============================================================
CREATE TABLE call_lists (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    description     TEXT,
    deleted_at      INTEGER,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

-- ============================================================
-- Call list membership (ordered by position)
-- ============================================================
CREATE TABLE call_list_contacts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    list_id         INTEGER NOT NULL REFERENCES call_lists(id) ON DELETE CASCADE,
    contact_id      INTEGER NOT NULL REFERENCES contacts(id)   ON DELETE CASCADE,
    position        INTEGER NOT NULL,                -- 0-based sort order
    status          TEXT    NOT NULL DEFAULT 'pending', -- pending | dialed | skipped
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    UNIQUE(list_id, contact_id)
);

CREATE INDEX idx_call_list_contacts_list ON call_list_contacts(list_id, position);

-- ============================================================
-- Call records — every inbound and outbound call
-- ============================================================
CREATE TABLE calls (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    direction       TEXT    NOT NULL,                -- inbound | outbound
    phone_number_id INTEGER NOT NULL REFERENCES phone_numbers(id),
    contact_id      INTEGER          REFERENCES contacts(id),
    remote_number   TEXT    NOT NULL,                -- E.164
    disposition     TEXT,   -- interested|not_interested|callback|voicemail|no_answer|busy|dnc|failed
    started_at      INTEGER NOT NULL,
    answered_at     INTEGER,
    ended_at        INTEGER,
    duration_ms     INTEGER,
    recording_path  TEXT,
    notes           TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX idx_calls_contact      ON calls(contact_id);
CREATE INDEX idx_calls_started_at   ON calls(started_at);
CREATE INDEX idx_calls_phone_number ON calls(phone_number_id);

-- ============================================================
-- SMS messages — inbound (via AWS relay) and outbound (Telnyx REST)
-- ============================================================
CREATE TABLE sms_messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    direction       TEXT    NOT NULL,                -- inbound | outbound
    phone_number_id INTEGER NOT NULL REFERENCES phone_numbers(id),
    contact_id      INTEGER          REFERENCES contacts(id),
    remote_number   TEXT    NOT NULL,                -- E.164
    body            TEXT    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'delivered', -- pending | delivered | failed
    sent_at         INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX idx_sms_contact  ON sms_messages(contact_id);
CREATE INDEX idx_sms_sent_at  ON sms_messages(sent_at);

-- ============================================================
-- Power dialer sessions
-- ============================================================
CREATE TABLE power_dialer_sessions (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    call_list_id     INTEGER NOT NULL REFERENCES call_lists(id),
    current_position INTEGER NOT NULL DEFAULT 0,
    state            TEXT    NOT NULL DEFAULT 'stopped', -- stopped | running | paused
    dialed_count     INTEGER NOT NULL DEFAULT 0,
    connected_count  INTEGER NOT NULL DEFAULT 0,
    started_at       INTEGER NOT NULL,
    ended_at         INTEGER,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);

-- ============================================================
-- Settings — key-value store for user preferences
-- ============================================================
CREATE TABLE settings (
    key        TEXT    PRIMARY KEY,
    value      TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
);
