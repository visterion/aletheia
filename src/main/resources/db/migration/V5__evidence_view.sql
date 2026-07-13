-- Per-counterparty evidence aggregates (spec §3). Read-only; never mutates raw data.
--
-- Joined to counterparties via the SAME identity expression CounterpartyResolver uses
-- (creditor_id > counterparty_iban > normalized counterparty_name), so a row here always
-- matches exactly the counterparty CounterpartyResolver created for it.
--
-- median_gap_days is computed via a derived table: LAG(booking_date) OVER (PARTITION BY
-- identity ORDER BY booking_date) to get the day-gap between consecutive same-counterparty
-- transactions, then percentile_cont(0.5) WITHIN GROUP over those gaps -- NOT a flat
-- aggregate (adversarial review m7).
--
-- spend_last_365d is computed relative to CURRENT_DATE (documented choice per spec §3,
-- not relative to the max booking_date in the data).
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
    a.direction
FROM aggregates a
JOIN counterparties c
    ON c.identity_type = a.identity_type AND c.identity_value = a.identity_value
LEFT JOIN median_gaps m
    ON m.identity_type = a.identity_type AND m.identity_value = a.identity_value;
