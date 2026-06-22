-- Add the missing `status` column to the calls table.
-- V1 defined the calls table without it, but the call repository reads and
-- writes status (ringing | active | ended | missed | failed). A default keeps
-- any existing rows valid.
ALTER TABLE calls ADD COLUMN status TEXT NOT NULL DEFAULT 'ended';
