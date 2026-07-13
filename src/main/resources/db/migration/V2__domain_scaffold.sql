-- Scaffold tables. Not populated by ingest; columns provisional, refined in step 4/5.

CREATE TABLE counterparties (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_type  TEXT    NOT NULL,               -- creditor_id | iban | name
    identity_value TEXT    NOT NULL,
    display_name   TEXT,
    reviewed       BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_counterparty_identity UNIQUE (identity_type, identity_value)
);

CREATE TABLE counterparty_tags (
    counterparty_id BIGINT NOT NULL REFERENCES counterparties (id),
    dimension       TEXT   NOT NULL,               -- domain | nature | necessity
    value           TEXT   NOT NULL,
    source          TEXT   NOT NULL,               -- auto | confirmed
    confidence      NUMERIC(4, 3),
    PRIMARY KEY (counterparty_id, dimension, value)
);

CREATE TABLE recurring (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    counterparty_id  BIGINT NOT NULL REFERENCES counterparties (id),
    amount_typical   NUMERIC(15, 2),
    amount_min       NUMERIC(15, 2),
    amount_max       NUMERIC(15, 2),
    interval_days    INT,
    interval_label   TEXT,
    first_seen       DATE,
    last_seen        DATE,
    occurrence_count INT,
    source           TEXT   NOT NULL DEFAULT 'auto',
    confidence       NUMERIC(4, 3),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
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
