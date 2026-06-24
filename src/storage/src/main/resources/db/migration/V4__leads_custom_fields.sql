-- V4__leads_custom_fields.sql
-- Add custom fields + lifecycle status to leads; enforce one LIVE lead per phone;
-- add keyset + status indexes for server-side filtering and pagination.
-- Additive only — never modify V1–V3 once applied.

-- 1. New columns.
ALTER TABLE leads ADD COLUMN custom_fields TEXT;                        -- JSON object, nullable
ALTER TABLE leads ADD COLUMN lead_status   TEXT NOT NULL DEFAULT 'new'; -- NEW|CONTACTED|...

-- 2. Dedupe existing LIVE duplicate phones (keep the newest id) before the unique index.
UPDATE leads
   SET deleted_at = CAST(strftime('%s','now') AS INTEGER) * 1000
 WHERE deleted_at IS NULL
   AND id NOT IN (SELECT MAX(id) FROM leads WHERE deleted_at IS NULL GROUP BY phone);

-- 3. One live lead per phone (partial unique index = the CSV-import dedupe target).
CREATE UNIQUE INDEX idx_leads_phone_live ON leads(phone) WHERE deleted_at IS NULL;

-- 4. Status facet + keyset-pagination indexes.
CREATE INDEX idx_leads_lead_status ON leads(lead_status);
CREATE INDEX idx_leads_keyset      ON leads(created_at DESC, id DESC);
