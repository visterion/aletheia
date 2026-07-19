-- V17: cashflow_role_map — maps a counterparty tag value (domain|nature) to a cashflow role.
-- Consumed read-only by the `cashflow` tool. Runtime-editable (operator adds rows via SQL).
-- role 'expense' is the default and is NOT stored (a value absent from the table is expense).
-- Seed values are generic category words (no merchant names / IBANs) — safe for a public repo.

CREATE TABLE cashflow_role_map (
    dimension TEXT NOT NULL,
    value     TEXT NOT NULL,
    role      TEXT NOT NULL,
    CONSTRAINT pk_cashflow_role_map PRIMARY KEY (dimension, value),
    CONSTRAINT chk_cashflow_role
        CHECK (role IN ('income', 'saving', 'transfer', 'depot', 'passthrough')),
    CONSTRAINT chk_cashflow_dimension
        CHECK (dimension IN ('domain', 'nature'))
);

INSERT INTO cashflow_role_map (dimension, value, role) VALUES
    ('domain', 'einkommen',       'income'),
    ('domain', 'transfer-privat', 'transfer'),
    ('nature', 'umbuchung',       'transfer'),
    ('nature', 'investment',      'depot'),
    ('nature', 'zahlungsdienst',  'passthrough');
