-- V5__import_infrastructure.sql
-- CSV import pipeline: per-commit batch records (for the reconciling summary + undo),
-- reusable named column-mapping templates, and a batch stamp on each imported lead.
-- Additive only — never modify V1–V4 once applied.

-- 1. Stamp every imported lead with the batch that created it (enables Undo).
ALTER TABLE leads ADD COLUMN import_batch_id TEXT;
CREATE INDEX idx_leads_import_batch ON leads(import_batch_id);

-- 2. One row per import commit — drives the summary equation and Undo.
CREATE TABLE import_batches (
    id              TEXT PRIMARY KEY,            -- UUID, also stamped onto leads
    file_name       TEXT NOT NULL,
    default_country TEXT,                        -- ISO-3166 region applied to bare numbers
    created_count   INTEGER NOT NULL DEFAULT 0,
    updated_count   INTEGER NOT NULL DEFAULT 0,
    skipped_count   INTEGER NOT NULL DEFAULT 0,
    error_count     INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL
);
CREATE INDEX idx_import_batches_created_at ON import_batches(created_at DESC);

-- 3. Named, reusable column-mapping templates keyed by a header signature.
CREATE TABLE import_mappings (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT NOT NULL,
    header_signature TEXT NOT NULL UNIQUE,       -- normalized, sorted header join
    mapping_json     TEXT NOT NULL,              -- column -> target field
    default_country  TEXT,
    target_list_id   INTEGER,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);
