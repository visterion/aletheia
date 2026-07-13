-- contracts has no natural key today, so link_contract has to fetchExists-then-insert/update
-- (TOCTOU), which can create duplicate rows for the same counterparty. contracts is empty in
-- production (spec §5 is still being wired up), so this ALTER carries no data-loss risk.

ALTER TABLE contracts ADD CONSTRAINT uq_contract_counterparty UNIQUE (counterparty_id);
