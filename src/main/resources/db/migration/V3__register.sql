-- Register tables (spec §4). The V2 scaffold tables were never populated by ingest
-- (ingest touches only `transactions` + `imports`), so this migration drops and
-- recreates them cleanly rather than surgically ALTERing. No data loss.

DROP TABLE IF EXISTS counterparty_history, contracts, recurring, counterparty_tags,
    counterparties CASCADE;

CREATE TABLE counterparties (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_type    TEXT    NOT NULL,               -- creditor_id | iban | name
    identity_value   TEXT    NOT NULL,
    display_name     TEXT,
    reviewed         BOOLEAN NOT NULL DEFAULT false,
    status           TEXT    NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'confirmed', 'dismissed')),
    dismissed_reason TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_counterparty_identity UNIQUE (identity_type, identity_value)
);

CREATE TABLE counterparty_tags (
    counterparty_id BIGINT NOT NULL REFERENCES counterparties (id),
    dimension       TEXT   NOT NULL
        CHECK (dimension IN ('domain', 'nature', 'necessity')),
    value           TEXT   NOT NULL,               -- free/emergent, no enum
    source          TEXT   NOT NULL CHECK (source IN ('auto', 'confirmed')),
    confidence      NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    PRIMARY KEY (counterparty_id, dimension, value)
);

CREATE TABLE recurring (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    counterparty_id  BIGINT NOT NULL REFERENCES counterparties (id),
    cadence          TEXT   NOT NULL
        CHECK (cadence IN ('monthly', 'quarterly', 'half_yearly', 'yearly', 'irregular')),
    typical_amount   NUMERIC(15, 2),
    amount_min       NUMERIC(15, 2),
    amount_max       NUMERIC(15, 2),
    first_seen       DATE,
    last_seen        DATE,
    occurrence_count INT,
    source           TEXT   NOT NULL DEFAULT 'auto' CHECK (source IN ('auto', 'confirmed')),
    confidence       NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_recurring_counterparty UNIQUE (counterparty_id)
);

CREATE TABLE contracts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    counterparty_id BIGINT NOT NULL REFERENCES counterparties (id),
    hivemem_cell_id TEXT,
    status          TEXT,
    confirmed_at    TIMESTAMPTZ,
    notes           TEXT
);

CREATE TABLE counterparty_history (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    counterparty_id BIGINT NOT NULL REFERENCES counterparties (id),
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    field           TEXT,
    old_value       TEXT,
    new_value       TEXT,
    source          TEXT,
    actor           TEXT
);
