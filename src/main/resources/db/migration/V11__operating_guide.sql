CREATE TABLE operating_guide (
    scope                   TEXT PRIMARY KEY DEFAULT 'default',
    workflow_md             TEXT NOT NULL,
    preferences_md          TEXT NOT NULL DEFAULT '',
    preferences_updated_at  TIMESTAMPTZ,
    preferences_updated_by  TEXT
);

INSERT INTO operating_guide (scope, workflow_md, preferences_md) VALUES ('default',
$md$# How to work with Aletheia

Aletheia turns bank bookings into a complete, evidenced register of recurring
obligations. Deterministic substrate first, then you (the LLM) enrich.

## Core discipline
- **Propose, a human confirms.** classify_counterparty / mark_recurring write source=auto
  (a proposal). Only confirm_counterparty resolves an obligation. Never count auto as done.
- **Never guess recurring.** A recurring obligation is only real once confirmed.
- **Keep merchant identity distinct.** Creditor-id / IBAN / name stay separate (1&1,
  Telekom, Deutsche Glasfaser are three entries).

## Make payment services transparent
- Payment intermediaries (PayPal, Klarna, LogPay, Adyen, card acquirers) are pass-through:
  the customer wants to see the merchant behind them, not the intermediary.
- PayPal is resolved automatically by the substrate. For the others, when you can identify
  the real merchant from the booking's remittance or its counter-entry (the Gegenbuchung,
  e.g. the matching per-merchant charge on the card account), re-attribute the booking to
  that merchant.

## Draining noise
- Non-obligations (own/family transfers, securities/investments, payment-service
  passthroughs) are not obligations. Drain them from the review queue in one call with the
  batch where-sweep, e.g. dismiss_counterparty(where={domainIn:["transfer-privat"],
  reviewed:false, hasContract:false}, reason=...).

## Preferences
See the customer preferences section returned by wake_up. When you learn a durable
preference, record it with update_preferences.
$md$,
'');
