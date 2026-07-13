# Aletheia

> ἀλήθεια, "un-forgetting", the opposite of Lethe. A household MCP server that turns a
> bank-account export into a complete, evidenced register of recurring obligations such as
> insurance, subscriptions and contracts. A recurring payment always shows up as a debit, so
> the account already contains the full list.

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-26-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.1-6DB33F)](https://spring.io/projects/spring-boot)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-6E56CF)](https://modelcontextprotocol.io)

## Why

Consumer finance tools such as Finanzguru and Banking4 sort transactions into a fixed taxonomy
and a set of predefined views. That works for browsing, but it makes ad-hoc questions hard to
answer. Aletheia exposes tools over an MCP server instead, so questions are answered through a
conversation. Because HiveMem is also an MCP server, a question like "which recurring payment has
no matching contract in my records?" can be answered across both systems in one prompt.

There is no live banking connection. A 24-month Subsembly JSON export from Banking4 already
contains every recurring debit, so that export is the only input. No PSD2, no OAuth to the bank.

## Architecture

```
Banking4 -> Subsembly JSON export -> Ingest -> Postgres -> Aletheia MCP <-> Claude <-> HiveMem MCP
```

- No GUI.
- No LLM in the ingest path. Raw data goes in first, then deterministic rules run (creditor ID),
  and only the remainder is enriched by an LLM, once per merchant.
- Runtime: deployed alongside HiveMem and Agora (Vistierie ecosystem).

## Pipeline

1. **Ingest.** Dumb, lossless and idempotent. A deterministic hash over the identifying fields is
   the natural key (`UNIQUE`, `ON CONFLICT DO NOTHING`), so re-imports with overlapping periods
   skip rows that already exist. Two genuinely identical bookings on the same day are kept apart by
   an `occurrence_index`, so they are not lost. An `imports` table records file, period, and the
   number of new and skipped rows.
2. **Deterministic rules.** The creditor ID identifies SEPA-debit counterparties. The GVC / booking
   key separates debit, transfer and card payments. Recurrence is found by interval clustering with
   an amount tolerance.
3. **LLM enrichment.** Applied only to the remainder, once per merchant. 300 REWE bookings map to
   one merchant and one classification.
4. **Dialogue.** The analysis happens in conversation with Claude.

## Tagging model

There are two separate levels:

- **Merchant identity** is deterministic (creditor ID or IBAN) and is kept distinct. 1&1, Telekom
  and Deutsche Glasfaser stay as three entries, so a case of paying for three contracts stays
  visible.
- **Tags** are soft and multi-dimensional, attached to the merchant: `domain:`, `nature:`,
  `necessity:`. A tag change applies retroactively to all of a merchant's transactions, with no
  reprocessing.

Recurring payments are never guessed. `source` and `confidence` distinguish `auto` from
`confirmed`; `auto` does not count as resolved. The review queue only drains through `confirmed` or
`dismissed`. Claude proposes a classification and a person confirms it.

## Status

Skeleton. The Postgres schema and ingest are not written yet, because the schema is derived from the
real Subsembly field names rather than guessed. The project is waiting on the export.

### Roadmap

1. Repo skeleton (structure, license, .gitignore, README). Done.
2. Subsembly JSON export from Banking4, used to derive the schema from real field names.
3. Postgres schema and ingest (dumb, lossless, idempotent, no LLM).
4. Deterministic recurring-payment detection (creditor ID, amount tolerance, interval clustering).
5. MCP server (tool set below) and auth analog to HiveMem.

### Planned tool set

**Read:** `list_recurring`, `query_spending`, `merchant_history`, `get_review_queue`,
`list_unmatched_recurring`, `sql_query` (read-only escape hatch).

**Write:** `classify_merchant`, `confirm_merchant`, `link_contract`, `dismiss`.

## Security

This repository is open source and Aletheia handles real account data, so:

- No real account data in the repo. No IBANs, creditor IDs, merchant names or sample exports. Test
  data is synthetic. `.env`, database dumps and exports are gitignored.
- `sql_query` is strictly read-only through a dedicated database user (SELECT only).
- Read and write scopes are separated. Write tools have no right to change transactions.
- Auth uses OAuth 2.1 with PKCE, discovery and dynamic client registration over Streamable HTTP,
  hand-rolled analog to HiveMem. A shared authorization server in Vistierie is the target, to avoid
  a third OAuth copy.

## Build

Java 26, Maven (wrapper included), Postgres.

```bash
./mvnw verify
```

## License

[MIT](LICENSE), © 2026 vivu
