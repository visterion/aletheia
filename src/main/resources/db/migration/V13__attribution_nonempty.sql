-- #43: attributed_name, when present, must be non-blank. Backstop for the manual reattribution
-- tool (a blank/whitespace name is rejected with 400 before the write, so this CHECK only ever
-- fires on a programming error). btrim so whitespace-only is also caught, mirroring the tool
-- guard. Complements chk_transactions_attribution_pair (V12).
ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_attributed_name_nonempty
    CHECK (attributed_name IS NULL OR btrim(attributed_name) <> '');
