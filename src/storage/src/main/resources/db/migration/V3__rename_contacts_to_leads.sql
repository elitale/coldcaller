-- V3__rename_contacts_to_leads.sql
-- Rename the "contact" domain concept to "lead" across the schema.
-- FlywayDB migration — never modify this file once applied.
-- SQLite rewrites foreign-key references in child tables automatically when a
-- parent table is renamed (RENAME COLUMN / RENAME TO require SQLite >= 3.25).

-- 1. Drop indexes that reference the tables / columns being renamed.
DROP INDEX IF EXISTS idx_contacts_phone;
DROP INDEX IF EXISTS idx_contacts_deleted_at;
DROP INDEX IF EXISTS idx_contacts_dnc;
DROP INDEX IF EXISTS idx_call_list_contacts_list;
DROP INDEX IF EXISTS idx_calls_contact;
DROP INDEX IF EXISTS idx_sms_contact;

-- 2. Rename the core table. Foreign keys in calls, sms_messages and the join
--    table are repointed to leads(id) automatically.
ALTER TABLE contacts RENAME TO leads;

-- 3. Rename the join table and its foreign-key column.
ALTER TABLE call_list_contacts RENAME TO call_list_leads;
ALTER TABLE call_list_leads RENAME COLUMN contact_id TO lead_id;

-- 4. Rename the foreign-key columns on calls and sms_messages.
ALTER TABLE calls        RENAME COLUMN contact_id TO lead_id;
ALTER TABLE sms_messages RENAME COLUMN contact_id TO lead_id;

-- 5. Recreate indexes with lead-based names.
CREATE INDEX idx_leads_phone          ON leads(phone);
CREATE INDEX idx_leads_deleted_at     ON leads(deleted_at);
CREATE INDEX idx_leads_dnc            ON leads(dnc);
CREATE INDEX idx_call_list_leads_list ON call_list_leads(list_id, position);
CREATE INDEX idx_calls_lead           ON calls(lead_id);
CREATE INDEX idx_sms_lead             ON sms_messages(lead_id);
