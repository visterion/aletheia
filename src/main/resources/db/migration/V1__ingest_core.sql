CREATE TABLE imports (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name             TEXT        NOT NULL,
    file_sha256           TEXT        NOT NULL UNIQUE,
    account_id            TEXT,
    period_start          DATE,
    period_end            DATE,
    rows_booked           INT         NOT NULL DEFAULT 0,
    rows_new              INT         NOT NULL DEFAULT 0,
    rows_skipped          INT         NOT NULL DEFAULT 0,
    rows_pending_ignored  INT         NOT NULL DEFAULT 0,
    imported_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    content_hash                TEXT    NOT NULL,
    occurrence_index            INT     NOT NULL DEFAULT 0,
    import_id                   BIGINT  NOT NULL REFERENCES imports (id),
    account_id                  TEXT,
    booking_date                DATE    NOT NULL,
    value_date                  DATE,
    amount                      NUMERIC(15, 2) NOT NULL,
    currency                    TEXT    NOT NULL,
    direction                   TEXT    NOT NULL,
    booking_status              TEXT    NOT NULL,
    booking_text                TEXT,
    remittance_info             TEXT,
    gvc                         TEXT,
    gvc_extension               TEXT,
    purpose_code                TEXT,
    counterparty_name           TEXT,
    counterparty_ultimate_name  TEXT,
    counterparty_iban           TEXT,
    counterparty_bic            TEXT,
    creditor_id                 TEXT,
    mandate_id                  TEXT,
    end_to_end_id               TEXT,
    subsembly_id                TEXT,
    raw                         JSONB   NOT NULL,
    CONSTRAINT uq_transactions_natural_key UNIQUE (content_hash, occurrence_index)
);

CREATE INDEX idx_transactions_creditor_id      ON transactions (creditor_id);
CREATE INDEX idx_transactions_counterparty_iban ON transactions (counterparty_iban);
CREATE INDEX idx_transactions_booking_date      ON transactions (booking_date);
CREATE INDEX idx_transactions_amount            ON transactions (amount);
