# Aletheia

> ἀλήθεια — *un-forgetting*, the opposite of Lethe. A household MCP server that turns your
> bank-account export into a complete, evidenced register of recurring obligations
> (insurance, subscriptions, contracts) — because **what is not debited does not exist**,
> the account *is* the register.

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-26-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.1-6DB33F)](https://spring.io/projects/spring-boot)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-6E56CF)](https://modelcontextprotocol.io)

---

## Why

Consumer finance tools (Finanzguru, Banking4) **categorise**; they don't **answer questions**.
They give you a fixed taxonomy and pre-thought views — the exact prison this project avoids.
Aletheia exposes *tools plus dialogue* over an MCP server instead of a GUI, so the core
question — *"which recurring payment has no matching contract in my records?"* — becomes a
single prompt across two MCP systems (Aletheia + HiveMem).

No live banking API, no PSD2/OAuth-to-the-bank. A 24-month **Subsembly JSON export** from
Banking4 contains every recurring debit in full. That is the entire input.

## Architecture

```
Banking4 → Subsembly JSON export → Ingest → Postgres → Aletheia MCP ⟷ Claude ⟷ HiveMem MCP
```

- **No GUI.** A GUI only answers pre-thought questions.
- **No LLM in the ingest path.** Raw in → deterministic rules (creditor-ID!) → LLM enrichment
  *per merchant*, never per transaction.
- **Runtime:** sits alongside HiveMem and Agora (Vistierie ecosystem).

## Pipeline

1. **Ingest** — dumb, lossless, **idempotent**. Deterministic hash over the identifying fields
   is the natural key (`UNIQUE`, `ON CONFLICT DO NOTHING`). Re-imports with overlapping periods
   skip already-seen rows; two genuinely-identical bookings on the same day are disambiguated by
   an `occurrence_index` and are *not* swallowed. An `imports` table records file/period/new/skipped.
2. **Deterministic rules** — creditor-ID identifies SEPA-debit counterparties; GVC/booking key
   distinguishes debit vs. transfer vs. card; recurrence = interval clustering + amount tolerance.
3. **LLM enrichment** — only the remainder, **per merchant** (300 REWE bookings = 1 classification).
4. **Dialogue** — the real work happens in conversation with Claude, not in a batch job.

## Tagging model (two strictly separate levels)

- **Merchant identity** (deterministic, creditor-ID/IBAN) — *never* merged. 1&1, Telekom and
  Deutsche Glasfaser stay three entries, or you can't see you're paying three times.
- **Tags** (soft, multi-dimensional, on the *merchant*): `domain:`, `nature:`, `necessity:`.
  Tag changes apply retroactively to all of a merchant's transactions — no reprocessing.

**Recurring is never guessed.** `source`/`confidence` = `auto` vs. `confirmed`; `auto` does not
count as resolved. The review queue only drains via `confirmed`/`dismissed`. Claude proposes;
it does not tick things off.

## Status

🚧 **Skeleton.** The Postgres schema and ingest are intentionally **not yet written** — the
schema is derived from the *real* Subsembly field names, not guessed. Waiting on the export.

### Roadmap

1. ✅ Repo skeleton (structure, license, .gitignore, README)
2. ⏳ **Subsembly JSON export** from Banking4 — schema derived from real field names
3. Postgres schema + ingest (dumb, lossless, idempotent; no LLM)
4. Deterministic recurring-payment detection (creditor-ID, amount tolerance, interval clustering)
5. MCP server (tool set below) + auth analog to HiveMem

### Planned tool set

**Read:** `list_recurring`, `query_spending`, `merchant_history`, `get_review_queue`,
`list_unmatched_recurring`, `sql_query` (read-only escape hatch).
**Write:** `classify_merchant`, `confirm_merchant`, `link_contract`, `dismiss`.

## Security

This repository is **open source** and Aletheia touches **real account data**. Therefore:

- **No real account data in the repo** — no IBANs, creditor IDs, merchant names, or sample
  exports. Test data is synthetic. `.env`, DB dumps and exports are gitignored.
- `sql_query` is strictly read-only via a dedicated DB user (SELECT only).
- Read/write scopes are separated; write tools have no right to mutate transactions.
- Auth: OAuth 2.1 + PKCE, discovery + dynamic client registration, Streamable HTTP —
  **hand-rolled analog to HiveMem** (a shared Authorization Server in Vistierie is the target,
  to avoid a third OAuth copy-paste).

## Build

Java 26, Maven (wrapper included), Postgres.

```bash
./mvnw verify
```

## License

[MIT](LICENSE) © 2026 vivu
