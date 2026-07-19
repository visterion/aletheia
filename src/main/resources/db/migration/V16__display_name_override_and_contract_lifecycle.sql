-- V16: P2 manual display-name override + P3 contract lifecycle (ended + end_date).
-- Additive only; existing data (status IN open|confirmed|dismissed) passes the widened CHECK.

ALTER TABLE counterparties ADD COLUMN display_name_override TEXT NULL;

-- Postgres cannot widen a CHECK in place; the V9 inline unnamed CHECK is auto-named
-- contracts_status_check. Drop and re-add with 'ended' included.
ALTER TABLE contracts DROP CONSTRAINT contracts_status_check;
ALTER TABLE contracts ADD CONSTRAINT contracts_status_check
    CHECK (status IN ('open', 'confirmed', 'dismissed', 'ended'));

ALTER TABLE contracts ADD COLUMN end_date DATE NULL;
