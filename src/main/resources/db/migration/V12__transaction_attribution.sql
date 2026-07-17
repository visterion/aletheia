-- #29 PayPal transparency: per-transaction attribution override + attribution-aware evidence.
-- attributed_name holds the RAW parsed merchant; normalization happens in the identity
-- derivation (same as counterparty_name). attribution_source: 'paypal' (deterministic) or
-- 'manual' (#43). Both-or-neither mirrors chk_transactions_split_parent_pair (V10).
ALTER TABLE transactions ADD COLUMN attributed_name    TEXT;
ALTER TABLE transactions ADD COLUMN attribution_source TEXT
    CHECK (attribution_source IN ('paypal', 'manual'));
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_attribution_pair CHECK (
    (attributed_name IS NULL     AND attribution_source IS NULL)
 OR (attributed_name IS NOT NULL AND attribution_source IS NOT NULL));

-- Rewrite evidence views to add the highest-priority attributed_name identity branch.
-- Output columns unchanged from V10 (consumed by ReadTools / jOOQ codegen).
DROP VIEW IF EXISTS v_contract_evidence;
DROP VIEW IF EXISTS v_counterparty_evidence;

-- v_counterparty_evidence: V10 body verbatim, with the attributed branch prepended.
CREATE VIEW v_counterparty_evidence AS
WITH identified AS (
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

-- v_contract_evidence: resolved per-transaction (identity, mandate); attributed rows key to
-- (name, normalized merchant, 'attributed'), others to (creditor_id, creditor_id, mandate_id).
-- Output columns identical to V9/V10. Join is generic on (identity_type, identity_value).
CREATE VIEW v_contract_evidence AS
WITH resolved AS (
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
per_mandate AS (
    SELECT
        identity_type,
        identity_value,
        mandate_id,
        booking_date,
        amount,
        direction,
        booking_date - LAG(booking_date) OVER (
            PARTITION BY identity_type, identity_value, mandate_id ORDER BY booking_date
        ) AS gap_days
    FROM resolved
),
agg AS (
    SELECT
        identity_type,
        identity_value,
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
    GROUP BY identity_type, identity_value, mandate_id
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
    ON c.identity_type = a.identity_type AND c.identity_value = a.identity_value;
