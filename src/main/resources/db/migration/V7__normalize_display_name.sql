-- V7__normalize_display_name.sql
-- Backfill: the resolver upserts with ON CONFLICT DO NOTHING and never updates existing rows,
-- so re-normalization of already-resolved names must happen here (spec §C, V7).
-- Collapses internal whitespace; keeps original case and umlauts (unlike the identity key).
UPDATE counterparties
SET display_name = trim(regexp_replace(normalize(display_name, NFC), '\s+', ' ', 'g'))
WHERE display_name IS NOT NULL
  AND display_name <> trim(regexp_replace(normalize(display_name, NFC), '\s+', ' ', 'g'));
