# 03, Context and Scope

## Business Context

```
                 Subsembly JSON export
Banking4  ------------------------------>  Aletheia MCP  <-->  Claude  <-->  HiveMem MCP
(user's banks)      (file, no live API)     (this repo)                     (records/evidence)
```

- **Banking4 (Subsembly)** produces the Subsembly JSON export. It is lossless and holds the complete
  internal field model. It is the only data source. A reference format for field comparison is
  CAMT 052 v08.
- **Aletheia** ingests the export into Postgres, applies deterministic rules, enriches per merchant,
  and exposes MCP tools.
- **Claude** is the interface. It reads Aletheia's tools and HiveMem's tools in one conversation.
- **HiveMem** holds contract evidence (attachments, KG facts for amounts and terms). Aletheia links
  a confirmed merchant or contract to a HiveMem cell.

## Technical Context

- **Transport:** MCP over Streamable HTTP, the same shape as HiveMem and Agora.
- **Auth:** OAuth 2.1 with PKCE, discovery and dynamic client registration, hand-rolled analog to
  HiveMem. A shared authorization server in Vistierie is the target.
- **Persistence:** Postgres via jOOQ. The schema is owned by Flyway.
- **Runtime:** deployed alongside HiveMem and Agora (Vistierie ecosystem).

## Scope boundary

In scope: ingest, deterministic recurring detection, per-merchant tagging, MCP query and write
tools, and contract linking to HiveMem. Out of scope (Phase 2): receipt and line-item digitisation,
and any live bank connection.
