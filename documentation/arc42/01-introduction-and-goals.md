# 01, Introduction and Goals

## What Aletheia is

Aletheia (ἀλήθεια, "un-forgetting", the opposite of Lethe) is a household MCP server. It turns a
bank-account export into a complete, evidenced register of recurring obligations: insurances,
subscriptions and contracts. A recurring payment always shows up as a debit, so the account already
contains the full list.

## Goals

1. **Completeness of obligations.** Surface every recurring payment so that no forgotten contract
   stays invisible. This is the primary goal. Spending analysis is a by-product.
2. **Answer questions through tools.** Expose tools over MCP and answer questions in a conversation,
   rather than through a GUI with predefined views.
3. **Cross-system reasoning.** Because HiveMem is also an MCP server, a question like "which
   recurring payment has no matching contract in my records?" can be answered across both systems in
   one prompt.

## Non-Goals

- No GUI.
- No live banking API, PSD2, or OAuth to the bank. A 24-month export is sufficient.
- No LLM in the ingest path.
- Receipt and shopping-basket digitisation is Phase 2.

## Quality Goals

| Priority | Quality         | Scenario                                                                                     |
|----------|-----------------|----------------------------------------------------------------------------------------------|
| 1        | Data integrity  | Re-importing overlapping exports never duplicates and never drops a real, identical same-day booking. |
| 2        | Confidentiality | Public repo with real account data, so no account data or secrets are ever committed.        |
| 3        | Correctness     | Recurring detection is deterministic; recurring payments are confirmed, not guessed (`auto` is not `confirmed`). |
