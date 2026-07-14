-- TP1: the contract entity (creditor_id, mandate_id). See
-- docs/superpowers/specs/2026-07-14-tp1-contract-level-design.md.
--
-- contracts is redefined to BE the contract entity (identity + human state + HiveMem link).
-- It is empty in production, so a drop/recreate is safe. DROP drops its grants; grants are
-- re-applied manually on prod after deploy (same one-time-manual handling as the original
-- register tables — no GRANT/CREATE ROLE in Flyway per project convention, V4__auth.sql:4).

DROP TABLE contracts;

CREATE TABLE contracts (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    counterparty_id  BIGINT NOT NULL REFERENCES counterparties (id),
    mandate_id       TEXT,                         -- NULL = the counterparty's mandate-less obligation
    source           TEXT NOT NULL DEFAULT 'auto'
        CHECK (source IN ('auto', 'confirmed')),
    confidence       NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    status           TEXT NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'confirmed', 'dismissed')),
    dismissed_reason TEXT,
    hivemem_cell_id  TEXT,
    confirmed_at     TIMESTAMPTZ,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_contract_counterparty_mandate
        UNIQUE NULLS NOT DISTINCT (counterparty_id, mandate_id)
);

CREATE INDEX idx_contracts_counterparty ON contracts (counterparty_id);

-- recurring: attach optionally to a contract; keep counterparty_id for mandate-less series.
ALTER TABLE recurring DROP CONSTRAINT uq_recurring_counterparty;
ALTER TABLE recurring ADD COLUMN contract_id BIGINT REFERENCES contracts (id);
ALTER TABLE recurring ADD CONSTRAINT uq_recurring_counterparty_contract
    UNIQUE NULLS NOT DISTINCT (counterparty_id, contract_id);

-- Per-(counterparty, mandate) evidence. Mirrors v_counterparty_evidence (V8) but at mandate
-- grain, so per-contract annual cost never falls back to the counterparty's whole debit.
-- Contracts only exist under creditor_id-identity counterparties, so grouping keys on
-- (creditor_id, mandate_id) and joins to counterparties on the creditor_id identity.
CREATE VIEW v_contract_evidence AS
WITH per_mandate AS (
    SELECT
        t.creditor_id,
        t.mandate_id,
        t.booking_date,
        t.amount,
        t.direction,
        t.booking_date - LAG(t.booking_date) OVER (
            PARTITION BY t.creditor_id, t.mandate_id ORDER BY t.booking_date
        ) AS gap_days
    FROM transactions t
    WHERE t.creditor_id IS NOT NULL AND t.mandate_id IS NOT NULL
),
agg AS (
    SELECT
        creditor_id,
        mandate_id,
        COUNT(*)                                  AS txn_count,
        MIN(booking_date)                         AS first_seen,
        MAX(booking_date)                         AS last_seen,
        MIN(amount)                               AS amount_min,
        MAX(amount)                               AS amount_max,
        AVG(amount)                               AS amount_avg,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY gap_days) AS median_gap_days,
        COALESCE(SUM(amount) FILTER (
            WHERE direction = 'DBIT'
              AND booking_date >= CURRENT_DATE - INTERVAL '365 days'), 0) AS debit_last_365d
    FROM per_mandate
    GROUP BY creditor_id, mandate_id
)
SELECT
    c.id AS counterparty_id,
    a.mandate_id,
    a.txn_count,
    a.first_seen,
    a.last_seen,
    a.median_gap_days,
    a.amount_min,
    a.amount_max,
    a.amount_avg,
    a.debit_last_365d
FROM agg a
JOIN counterparties c
    ON c.identity_type = 'creditor_id' AND c.identity_value = a.creditor_id;
