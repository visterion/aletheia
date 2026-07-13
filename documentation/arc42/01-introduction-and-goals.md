# 01 — Introduction and Goals

## What Aletheia is

Aletheia (ἀλήθεια — *un-forgetting*, the opposite of Lethe) is a household MCP server. It turns a
bank-account export into a **complete, evidenced register of recurring obligations** — insurances,
subscriptions, contracts. The guiding premise: *what is not debited does not exist*, so the account
is the single source of truth.

## Goals

1. **Completeness of obligations** — surface every recurring payment, so no forgotten contract stays
   invisible. This is the primary goal; spending analysis is a by-product.
2. **Questions, not categories** — expose *tools + dialogue* over MCP instead of a GUI with
   pre-thought views (the failure mode of Finanzguru/Banking4).
3. **Cross-system reasoning** — because HiveMem is also MCP, "which recurring payment has no matching
   contract in my records?" becomes a single prompt across two systems.

## Non-Goals

- No GUI.
- No live banking API / PSD2 / OAuth-to-the-bank — a 24-month export is sufficient.
- No LLM in the ingest path.
- Receipt / shopping-basket digitisation is explicitly **Phase 2**.

## Quality Goals

| Priority | Quality           | Scenario                                                                 |
|----------|-------------------|--------------------------------------------------------------------------|
| 1        | Data integrity    | Re-importing overlapping exports never duplicates and never drops a real, identical same-day booking. |
| 2        | Confidentiality   | Public repo + real account data → no account data or secrets ever committed. |
| 3        | Correctness       | Recurring detection is deterministic; nothing recurring is *guessed* (`auto` ≠ `confirmed`). |
