-- Counterparty merge (sub-project A/P1): fold fragmented sources under one canonical target.
-- counterparty_alias maps a source identity (identity_type, identity_value) to the canonical
-- counterparty it should pool under; merged_into on counterparties records that a counterparty
-- itself has been folded (bookkeeping for later tasks, not yet read by the views below).
CREATE TABLE counterparty_alias (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_type             TEXT NOT NULL,
    identity_value            TEXT NOT NULL,
    canonical_counterparty_id BIGINT NOT NULL REFERENCES counterparties (id),
    reason                    TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_counterparty_alias UNIQUE (identity_type, identity_value)
);
CREATE INDEX ix_counterparty_alias_canonical ON counterparty_alias (canonical_counterparty_id);

ALTER TABLE counterparties ADD COLUMN merged_into BIGINT REFERENCES counterparties (id);

-- Rewrite evidence views to resolve every transaction's *effective* counterparty as
-- COALESCE(counterparty_alias.canonical_counterparty_id, own counterparties.id), pooling any
-- aliased source under its target. Output columns unchanged from V12 (consumed by ReadTools /
-- jOOQ codegen). With no alias rows, effective_cp == own.id -- a bijection with
-- (identity_type, identity_value) since counterparties is UNIQUE on it -- so this is
-- byte-identical to V12 for un-merged data.
DROP VIEW IF EXISTS v_contract_evidence;
DROP VIEW IF EXISTS v_counterparty_evidence;

CREATE VIEW v_counterparty_evidence AS
WITH base AS (
    SELECT
        t.booking_date,
        t.amount,
        t.direction,
        CASE
            WHEN t.attributed_name IS NOT NULL THEN 'name'
            WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
            WHEN t.counterparty_iban IS NOT NULL THEN 'iban'
            WHEN t.counterparty_name IS NOT NULL THEN 'name'
        END AS identity_type,
        CASE
            WHEN t.attributed_name IS NOT NULL THEN
                upper(trim(regexp_replace(normalize(t.attributed_name, NFC), '\s+', ' ', 'g')))
            WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
            WHEN t.counterparty_iban IS NOT NULL THEN t.counterparty_iban
            WHEN t.counterparty_name IS NOT NULL THEN
                upper(trim(regexp_replace(normalize(t.counterparty_name, NFC), '\s+', ' ', 'g')))
        END AS identity_value
    FROM transactions t
    WHERE NOT EXISTS (
        SELECT 1 FROM transactions c
        WHERE c.split_parent_content_hash = t.content_hash
          AND c.split_parent_occurrence_index = t.occurrence_index
    )
),
identified AS (
    SELECT
        b.booking_date,
        b.amount,
        b.direction,
        COALESCE(al.canonical_counterparty_id, own.id) AS effective_cp
    FROM base b
    LEFT JOIN counterparty_alias al
        ON al.identity_type = b.identity_type AND al.identity_value = b.identity_value
    LEFT JOIN counterparties own
        ON own.identity_type = b.identity_type AND own.identity_value = b.identity_value
    WHERE b.identity_type IS NOT NULL
      AND COALESCE(al.canonical_counterparty_id, own.id) IS NOT NULL
),
gaps AS (
    SELECT
        effective_cp,
        booking_date - LAG(booking_date) OVER (
            PARTITION BY effective_cp ORDER BY booking_date
        ) AS gap_days
    FROM identified
),
median_gaps AS (
    SELECT
        effective_cp,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY gap_days) AS median_gap_days
    FROM gaps
    WHERE gap_days IS NOT NULL
    GROUP BY effective_cp
),
aggregates AS (
    SELECT
        effective_cp,
        COUNT(*)                                                              AS txn_count,
        MIN(booking_date)                                                     AS first_seen,
        MAX(booking_date)                                                     AS last_seen,
        (MAX(booking_date) - MIN(booking_date))                               AS span_days,
        SUM(amount)                                                           AS total_amount,
        MIN(amount)                                                           AS amount_min,
        MAX(amount)                                                           AS amount_max,
        AVG(amount)                                                           AS amount_avg,
        STDDEV(amount)                                                        AS amount_stddev,
        COALESCE(
            SUM(amount) FILTER (WHERE booking_date >= CURRENT_DATE - INTERVAL '365 days'),
            0)                                                                AS spend_last_365d,
        COALESCE(
            SUM(amount) FILTER (WHERE direction = 'DBIT'
                AND booking_date >= CURRENT_DATE - INTERVAL '365 days'),
            0)                                                                AS debit_last_365d,
        COALESCE(
            SUM(amount) FILTER (WHERE direction = 'CRDT'
                AND booking_date >= CURRENT_DATE - INTERVAL '365 days'),
            0)                                                                AS credit_last_365d,
        COALESCE(
            SUM(amount) FILTER (WHERE direction = 'CRDT'),
            0)                                                                AS credit_total,
        mode() WITHIN GROUP (ORDER BY direction)                              AS direction
    FROM identified
    GROUP BY effective_cp
)
SELECT
    a.effective_cp AS counterparty_id,
    a.txn_count,
    a.first_seen,
    a.last_seen,
    a.span_days,
    a.total_amount,
    a.amount_min,
    a.amount_max,
    a.amount_avg,
    a.amount_stddev,
    m.median_gap_days,
    a.spend_last_365d,
    a.debit_last_365d,
    a.credit_last_365d,
    a.credit_total,
    a.direction
FROM aggregates a
LEFT JOIN median_gaps m
    ON m.effective_cp = a.effective_cp;

CREATE VIEW v_contract_evidence AS
WITH base AS (
    SELECT
        t.booking_date,
        t.amount,
        t.direction,
        CASE
            WHEN t.attributed_name IS NOT NULL THEN 'name'
            WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
        END AS identity_type,
        CASE
            WHEN t.attributed_name IS NOT NULL THEN
                upper(trim(regexp_replace(normalize(t.attributed_name, NFC), '\s+', ' ', 'g')))
            WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
        END AS identity_value,
        CASE
            WHEN t.attributed_name IS NOT NULL THEN 'attributed'
            ELSE t.mandate_id
        END AS mandate_id
    FROM transactions t
    WHERE NOT EXISTS (
        SELECT 1 FROM transactions c
        WHERE c.split_parent_content_hash = t.content_hash
          AND c.split_parent_occurrence_index = t.occurrence_index
    )
      AND (t.attributed_name IS NOT NULL
           OR (t.creditor_id IS NOT NULL AND t.mandate_id IS NOT NULL))
),
resolved AS (
    SELECT
        b.booking_date,
        b.amount,
        b.direction,
        b.mandate_id,
        COALESCE(al.canonical_counterparty_id, own.id) AS effective_cp
    FROM base b
    LEFT JOIN counterparty_alias al
        ON al.identity_type = b.identity_type AND al.identity_value = b.identity_value
    LEFT JOIN counterparties own
        ON own.identity_type = b.identity_type AND own.identity_value = b.identity_value
    WHERE COALESCE(al.canonical_counterparty_id, own.id) IS NOT NULL
),
per_mandate AS (
    SELECT
        effective_cp,
        mandate_id,
        booking_date,
        amount,
        direction,
        booking_date - LAG(booking_date) OVER (
            PARTITION BY effective_cp, mandate_id ORDER BY booking_date
        ) AS gap_days
    FROM resolved
),
agg AS (
    SELECT
        effective_cp,
        mandate_id,
        COUNT(*)                                  AS txn_count,
        MIN(booking_date)                         AS first_seen,
        MAX(booking_date)                         AS last_seen,
        MIN(amount)                               AS amount_min,
        MAX(amount)                               AS amount_max,
        AVG(amount)                                AS amount_avg,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY gap_days) AS median_gap_days,
        COALESCE(SUM(amount) FILTER (
            WHERE direction = 'DBIT'
              AND booking_date >= CURRENT_DATE - INTERVAL '365 days'), 0) AS debit_last_365d
    FROM per_mandate
    GROUP BY effective_cp, mandate_id
)
SELECT
    a.effective_cp AS counterparty_id,
    a.mandate_id,
    a.txn_count,
    a.first_seen,
    a.last_seen,
    a.median_gap_days,
    a.amount_min,
    a.amount_max,
    a.amount_avg,
    a.debit_last_365d
FROM agg a;
