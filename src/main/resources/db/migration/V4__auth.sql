-- Auth schema (spec §4 / §6), ported from HiveMem's V0005__api_tokens.sql and
-- V0025__oauth.sql. Columns/FKs copied verbatim except api_tokens.role, whose CHECK
-- is adapted from HiveMem's four roles to Aletheia's three (reader/writer/admin).
-- No DB role GRANTs here — role creation + grants live in the compose Postgres init
-- script, not in Flyway migrations (spec §7).

CREATE TABLE api_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash  TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL CHECK (role IN ('reader', 'writer', 'admin')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_tokens_hash ON api_tokens (token_hash)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_tokens_name ON api_tokens (name);

-- OAuth 2.0 Authorization Server schema for MCP Custom Connectors
-- (Claude.ai Custom Connector, similar clients).
--
-- Public-client model with PKCE only — no client_secret. Clients register
-- themselves via Dynamic Client Registration (RFC 7591).
--
-- Tokens issued by this server are validated by the existing AuthFilter via
-- a separate lookup path; they are NOT stored in api_tokens to keep the
-- two issuance models cleanly separated and to avoid leaking OAuth-specific
-- columns (scope, client_id, expires) into the simpler api_tokens table.

CREATE TABLE oauth_clients (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id                   TEXT NOT NULL UNIQUE,
    client_name                 TEXT NOT NULL,
    redirect_uris               TEXT[] NOT NULL,
    grant_types                 TEXT[] NOT NULL DEFAULT ARRAY['authorization_code','refresh_token']::TEXT[],
    response_types              TEXT[] NOT NULL DEFAULT ARRAY['code']::TEXT[],
    token_endpoint_auth_method  TEXT NOT NULL DEFAULT 'none',  -- public client + PKCE
    scope                       TEXT NOT NULL DEFAULT 'read write',
    client_uri                  TEXT,
    logo_uri                    TEXT,
    contacts                    TEXT[],
    software_id                 TEXT,
    software_version            TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                  TIMESTAMPTZ
);

CREATE INDEX idx_oauth_clients_client_id ON oauth_clients (client_id)
    WHERE revoked_at IS NULL;

-- Short-lived authorization codes. PKCE challenge required (S256).
CREATE TABLE oauth_authorization_codes (
    code_hash                   TEXT PRIMARY KEY,            -- sha256 of the code
    client_id                   TEXT NOT NULL REFERENCES oauth_clients(client_id),
    redirect_uri                TEXT NOT NULL,
    scope                       TEXT NOT NULL,
    code_challenge              TEXT NOT NULL,
    code_challenge_method       TEXT NOT NULL DEFAULT 'S256',
    user_token_id               UUID NOT NULL REFERENCES api_tokens(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                  TIMESTAMPTZ NOT NULL,         -- typically created_at + 10 min
    consumed_at                 TIMESTAMPTZ                   -- set on first /token exchange; replays rejected
);

CREATE INDEX idx_oauth_codes_expiry ON oauth_authorization_codes (expires_at)
    WHERE consumed_at IS NULL;

-- Issued tokens. One row per access OR refresh token (kind disambiguates).
-- Refresh-token rotation: when an old refresh token is exchanged, it gets
-- revoked_at=now() and a new one is issued; replay attempts after rotation
-- detect compromise and revoke the entire chain (parent_id link).
CREATE TABLE oauth_tokens (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                        TEXT NOT NULL CHECK (kind IN ('access','refresh')),
    token_hash                  TEXT NOT NULL UNIQUE,         -- sha256 of the bearer
    client_id                   TEXT NOT NULL REFERENCES oauth_clients(client_id),
    user_token_id               UUID NOT NULL REFERENCES api_tokens(id),  -- the underlying Aletheia identity
    scope                       TEXT NOT NULL DEFAULT 'read write',
    parent_id                   UUID REFERENCES oauth_tokens(id),         -- for refresh-token rotation chain
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                  TIMESTAMPTZ NOT NULL,
    revoked_at                  TIMESTAMPTZ
);

CREATE INDEX idx_oauth_tokens_hash ON oauth_tokens (token_hash)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_oauth_tokens_expiry ON oauth_tokens (expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_oauth_tokens_parent ON oauth_tokens (parent_id)
    WHERE parent_id IS NOT NULL;
