# 03 — Context and Scope

## Business Context

```
                 Subsembly JSON export
Banking4  ─────────────────────────────►  Aletheia MCP  ◄────►  Claude  ◄────►  HiveMem MCP
(user's banks)      (file, no live API)     (this repo)                        (records/evidence)
```

- **Banking4 (Subsembly):** produces the `Subsembly JSON` export — lossless, complete internal field
  model. The only data source. Alternative reference format for field comparison: CAMT 052 v08.
- **Aletheia:** ingests the export into Postgres, applies deterministic rules, enriches per merchant,
  and exposes MCP tools.
- **Claude:** the interface — reads Aletheia's tools and HiveMem's tools in one conversation.
- **HiveMem:** holds contract evidence (attachments, KG facts for amounts/terms); Aletheia links a
  confirmed merchant/contract to a HiveMem cell.

## Technical Context

- **Transport:** MCP over Streamable HTTP (same shape as HiveMem/Agora).
- **Auth:** OAuth 2.1 + PKCE, discovery + dynamic client registration — hand-rolled analog to HiveMem;
  target is a shared Authorization Server in Vistierie.
- **Persistence:** Postgres via jOOQ; schema owned by Flyway.
- **Runtime:** deployed alongside HiveMem and Agora (Vistierie ecosystem).

## Scope boundary

In scope: ingest, deterministic recurring detection, per-merchant tagging, MCP query/write tools,
contract linking to HiveMem. Out of scope (Phase 2): receipt/line-item digitisation, any live bank
connection.
