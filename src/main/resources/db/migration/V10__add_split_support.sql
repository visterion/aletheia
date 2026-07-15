-- Add split support columns for logical child rows (parents keep NULL).
/* logical filter must match TransactionLayerSql.notExistsSupersededParent */

ALTER TABLE transactions ADD COLUMN split_parent_content_hash TEXT;
ALTER TABLE transactions ADD COLUMN split_parent_occurrence_index INT;

-- Both-or-neither: no half-set parent refs (no composite FK — soft backref is intentional).
ALTER TABLE transactions
  ADD CONSTRAINT chk_transactions_split_parent_pair
  CHECK (
    (split_parent_content_hash IS NULL AND split_parent_occurrence_index IS NULL)
    OR
    (split_parent_content_hash IS NOT NULL AND split_parent_occurrence_index IS NOT NULL)
  );

-- Children are synthetic (not bank imports) so allow NULL (spec).
ALTER TABLE transactions ALTER COLUMN import_id DROP NOT NULL;

CREATE INDEX idx_transactions_split_parent
  ON transactions (split_parent_content_hash, split_parent_occurrence_index)
  WHERE split_parent_content_hash IS NOT NULL;

-- TP2: recreate evidence views with logical filter (NOT EXISTS on split_parent_*).
-- This must live in V10 (after columns added) so that V5/V8/V9 (pre-column) apply cleanly.
-- Views now return only current logical positions; raw "transactions" table and sql_query
-- on it continue to expose parents+children. Updates use DROP/CREATE like V8 did.

DROP VIEW IF EXISTS v_contract_evidence;
DROP VIEW IF EXISTS v_counterparty_evidence;

-- v_counterparty_evidence (extended direction-split version, with logical filter in identified)
CREATE VIEW v_counterparty_evidence AS
WITH identified AS (
    SELECT
        t.booking_date,
        t.amount,
        t.direction,
        CASE
            WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
            WHEN t.counterparty_iban IS NOT NULL THEN 'iban'
            WHEN t.counterparty_name IS NOT NULL THEN 'name'
        END AS identity_type,
        CASE
            WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
            WHEN t.counterparty_iban IS NOT NULL THEN t.counterparty_iban
            WHEN t.counterparty_name IS NOT NULL THEN
                upper(trim(regexp_replace(normalize(t.counterparty_name, NFC), '\s+', ' ', 'g')))
        END AS identity_value
    FROM transactions t
    -- Core logical filter (TP2 spec §2.3): hide split parents. Exact form for consistency
    -- with ReadTools (aggregate, counterparty_transactions, IDENTITY subselect).
    WHERE NOT EXISTS (
        SELECT 1 FROM transactions c
        WHERE c.split_parent_content_hash = t.content_hash
          AND c.split_parent_occurrence_index = t.occurrence_index
    )
),
gaps AS (
    SELECT
        identity_type,
        identity_value,
        booking_date - LAG(booking_date) OVER (
            PARTITION BY identity_type, identity_value ORDER BY booking_date
        ) AS gap_days
    FROM identified
    WHERE identity_type IS NOT NULL
),
median_gaps AS (
    SELECT
        identity_type,
        identity_value,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY gap_days) AS median_gap_days
    FROM gaps
    WHERE gap_days IS NOT NULL
    GROUP BY identity_type, identity_value
),
aggregates AS (
    SELECT
        identity_type,
        identity_value,
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
    WHERE identity_type IS NOT NULL
    GROUP BY identity_type, identity_value
)
SELECT
    c.id AS counterparty_id,
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
JOIN counterparties c
    ON c.identity_type = a.identity_type AND c.identity_value = a.identity_value
LEFT JOIN median_gaps m
    ON m.identity_type = a.identity_type AND m.identity_value = a.identity_value;

-- v_contract_evidence (mandate grain, with logical filter in per_mandate)
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
    -- Core logical filter (exact NOT EXISTS, consistent with counterparty view + ReadTools).
    WHERE t.creditor_id IS NOT NULL
      AND t.mandate_id IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM transactions c
          WHERE c.split_parent_content_hash = t.content_hash
            AND c.split_parent_occurrence_index = t.occurrence_index
      )
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
