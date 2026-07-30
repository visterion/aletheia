-- V19: the product grain. A SEPA mandate that bundles several products currently produces one
-- contract whose annual cost is the sum of unrelated obligations. This migration adds the level
-- below the mandate: contracts become (counterparty_id, mandate_id, product).
--
-- See docs/superpowers/specs/2026-07-26-product-contract-grain-design.md §3.
--
-- Additive for every existing row: product is NULL everywhere until a rule exists, and
-- UNIQUE NULLS NOT DISTINCT (counterparty_id, mandate_id, NULL) is exactly the old key.

-- ---------------------------------------------------------------------------------------------
-- Rule per creditor. Deliberately empty in the repo: a hard-coded parser would put a real
-- creditor's remittance format into a public repository, so rows exist only on production.
-- The four roots_* counters ARE the residue surface -- list_product_rules and the wake_up
-- live-state block read them, so a creditor that reformats its remittance surfaces as a rising
-- roots_mismatched instead of vanishing into container logs.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE product_rules (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    creditor_id      TEXT NOT NULL,
    position_pattern TEXT NOT NULL,   -- matches ONE position; applied globally via find()
    enabled          BOOLEAN NOT NULL DEFAULT true,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_resolved_at TIMESTAMPTZ,
    roots_visited    INT NOT NULL DEFAULT 0,
    roots_split      INT NOT NULL DEFAULT 0,
    roots_stamped    INT NOT NULL DEFAULT 0,
    roots_mismatched INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_rules_creditor UNIQUE (creditor_id)
);

ALTER TABLE transactions ADD COLUMN product           TEXT;
ALTER TABLE transactions ADD COLUMN product_policy_no TEXT;

ALTER TABLE contracts ADD COLUMN product TEXT;

-- The grain change itself. NULLS NOT DISTINCT keeps the mandate-less contract unique per
-- counterparty, which three fetchOne() sites in WriteTools depend on.
ALTER TABLE contracts DROP CONSTRAINT uq_contract_counterparty_mandate;
ALTER TABLE contracts ADD CONSTRAINT uq_contract_counterparty_mandate_product
    UNIQUE NULLS NOT DISTINCT (counterparty_id, mandate_id, product);

-- A product contract always hangs off a mandate. Without this, a (cp, NULL, 'HEALTH') row would
-- be legal under the new key and would make WriteTools' three mandate-less fetchOne() lookups
-- (each filtering only on mandate_id IS NULL) throw TooManyRowsException. The resolver never
-- creates such a row -- its derivation requires a mandate -- but "the resolver won't" is not an
-- invariant, and this is the cheapest place to make it one.
ALTER TABLE contracts ADD CONSTRAINT chk_contracts_product_needs_mandate
    CHECK (product IS NULL OR mandate_id IS NOT NULL);

-- ---------------------------------------------------------------------------------------------
-- Normalization CHECKs, one level below V18's.
--
-- product is stored in identity-normalised form, so the creditor's mid-history capitalisation
-- change is a non-event rather than two contracts for the same product. The expression is
-- COPIED VERBATIM from V18 (which took it from substrate/NameNormalization); it is frozen by the
-- V15 evidence views, and a subtly different copy silently re-fragments identity -- which is
-- precisely the hole V18 exists to close.
--
--   identity = upper(trim(regexp_replace(normalize(x, NFC), '\s+', ' ', 'g')))
--
-- No backfill and no preflight are needed: both columns are new, so every existing row holds
-- NULL, and a CHECK only fails on FALSE.
--
-- product_policy_no is deliberately NOT constrained: it is stored verbatim and is part of no key.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE contracts ADD CONSTRAINT chk_contracts_product_normalized
    CHECK (product IS NULL
        OR product = upper(trim(regexp_replace(normalize(product, NFC), '\s+', ' ', 'g'))));

ALTER TABLE transactions ADD CONSTRAINT chk_transactions_product_normalized
    CHECK (product IS NULL
        OR product = upper(trim(regexp_replace(normalize(product, NFC), '\s+', ' ', 'g'))));

-- ---------------------------------------------------------------------------------------------
-- Rebuild v_contract_evidence at product grain. Copied verbatim from V15 with exactly four
-- changes, the same DROP/CREATE idiom V12 and V15 used:
--
--   1. product in the base/resolved projection
--   2. product in the LAG window's PARTITION BY
--   3. product in agg's GROUP BY
--   4. product in the output
--
-- (2) is the one that is easy to miss and impossible to see in a count or sum assertion: the
-- same-date sibling children of one split booking would otherwise land in a single gap series,
-- where the intra-booking gap of 0 dominates and drives median_gap_days to 0 -- turning the
-- cadence signal for every product contract into noise.
--
-- v_counterparty_evidence is untouched: tags and counterparty identity stay per counterparty.
-- ---------------------------------------------------------------------------------------------
DROP VIEW IF EXISTS v_contract_evidence;

CREATE VIEW v_contract_evidence AS
WITH base AS (
    SELECT
        t.booking_date,
        t.amount,
        t.direction,
        t.product,
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
        b.product,
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
        product,
        booking_date,
        amount,
        direction,
        booking_date - LAG(booking_date) OVER (
            PARTITION BY effective_cp, mandate_id, product ORDER BY booking_date
        ) AS gap_days
    FROM resolved
),
agg AS (
    SELECT
        effective_cp,
        mandate_id,
        product,
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
    GROUP BY effective_cp, mandate_id, product
)
SELECT
    a.effective_cp AS counterparty_id,
    a.mandate_id,
    a.product,
    a.txn_count,
    a.first_seen,
    a.last_seen,
    a.median_gap_days,
    a.amount_min,
    a.amount_max,
    a.amount_avg,
    a.debit_last_365d
FROM agg a;
