package de.visterion.aletheia.oauth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Persistence for OAuth 2.0 server state — clients, authorization codes, access/refresh
 * tokens. All bearer secrets are stored as SHA-256 hashes; the plaintext value is only ever
 * returned to the client at issue time.
 *
 * <p>Ported 1:1 from HiveMem's {@code com.hivemem.oauth.OAuthRepository}. Only {@link
 * #lookupActiveToken(String)} is currently consumed (by {@code AuthFilter}); the remaining
 * methods are carried over so the full OAuth authorization-code + token-issuance flow (Task 3,
 * the {@code oauth} controllers) has its persistence layer ready without a second port pass.
 */
@Repository
public class OAuthRepository {

  private final DSLContext dsl;

  public OAuthRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  // --- Clients --------------------------------------------------------

  public OAuthClient insertClient(
      String clientId,
      String clientName,
      List<String> redirectUris,
      List<String> grantTypes,
      List<String> responseTypes,
      String tokenEndpointAuthMethod,
      String scope,
      String clientUri,
      String logoUri,
      List<String> contacts,
      String softwareId,
      String softwareVersion) {
    Record row =
        dsl.fetchOne(
            """
                INSERT INTO oauth_clients (client_id, client_name, redirect_uris, grant_types,
                                            response_types, token_endpoint_auth_method, scope,
                                            client_uri, logo_uri, contacts, software_id, software_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING client_id, client_name, redirect_uris, grant_types, response_types,
                          token_endpoint_auth_method, scope, client_uri, logo_uri, contacts,
                          software_id, software_version, created_at
                """,
            clientId,
            clientName,
            redirectUris.toArray(String[]::new),
            grantTypes.toArray(String[]::new),
            responseTypes.toArray(String[]::new),
            tokenEndpointAuthMethod,
            scope,
            clientUri,
            logoUri,
            contacts == null ? null : contacts.toArray(String[]::new),
            softwareId,
            softwareVersion);
    return rowToClient(row);
  }

  public Optional<OAuthClient> findClient(String clientId) {
    Record row =
        dsl.fetchOne(
            """
                SELECT client_id, client_name, redirect_uris, grant_types, response_types,
                       token_endpoint_auth_method, scope, client_uri, logo_uri, contacts,
                       software_id, software_version, created_at
                FROM oauth_clients
                WHERE client_id = ? AND revoked_at IS NULL
                """,
            clientId);
    return Optional.ofNullable(row).map(OAuthRepository::rowToClient);
  }

  private static OAuthClient rowToClient(Record row) {
    return new OAuthClient(
        row.get("client_id", String.class),
        row.get("client_name", String.class),
        List.of(row.get("redirect_uris", String[].class)),
        List.of(row.get("grant_types", String[].class)),
        List.of(row.get("response_types", String[].class)),
        row.get("token_endpoint_auth_method", String.class),
        row.get("scope", String.class),
        row.get("client_uri", String.class),
        row.get("logo_uri", String.class),
        row.get("contacts", String[].class) == null
            ? List.of()
            : List.of(row.get("contacts", String[].class)),
        row.get("software_id", String.class),
        row.get("software_version", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  // --- Authorization codes -------------------------------------------

  public void insertAuthorizationCode(
      String codeHash,
      String clientId,
      String redirectUri,
      String scope,
      String codeChallenge,
      String codeChallengeMethod,
      UUID userTokenId,
      OffsetDateTime expiresAt) {
    dsl.execute(
        """
            INSERT INTO oauth_authorization_codes
                (code_hash, client_id, redirect_uri, scope, code_challenge,
                 code_challenge_method, user_token_id, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
            """,
        codeHash,
        clientId,
        redirectUri,
        scope,
        codeChallenge,
        codeChallengeMethod,
        userTokenId,
        expiresAt);
  }

  /**
   * Atomically consume an authorization code on first use. Returns the row only if it existed,
   * was unconsumed, and not yet expired. Subsequent calls with the same code return empty.
   */
  public Optional<AuthorizationCode> consumeAuthorizationCode(String codeHash) {
    Record row =
        dsl.fetchOne(
            """
                UPDATE oauth_authorization_codes
                   SET consumed_at = now()
                 WHERE code_hash = ?
                   AND consumed_at IS NULL
                   AND expires_at > now()
                RETURNING client_id, redirect_uri, scope, code_challenge,
                          code_challenge_method, user_token_id
                """,
            codeHash);
    if (row == null) return Optional.empty();
    return Optional.of(
        new AuthorizationCode(
            row.get("client_id", String.class),
            row.get("redirect_uri", String.class),
            row.get("scope", String.class),
            row.get("code_challenge", String.class),
            row.get("code_challenge_method", String.class),
            row.get("user_token_id", UUID.class)));
  }

  // --- Tokens --------------------------------------------------------

  public UUID insertToken(
      String kind,
      String tokenHash,
      String clientId,
      UUID userTokenId,
      String scope,
      UUID parentId,
      OffsetDateTime expiresAt) {
    Record row =
        dsl.fetchOne(
            """
                INSERT INTO oauth_tokens (kind, token_hash, client_id, user_token_id,
                                          scope, parent_id, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz)
                RETURNING id
                """,
            kind,
            tokenHash,
            clientId,
            userTokenId,
            scope,
            parentId,
            expiresAt);
    return row.get("id", UUID.class);
  }

  public Optional<TokenLookup> lookupActiveToken(String tokenHash) {
    Record row =
        dsl.fetchOne(
            """
                SELECT id, kind, client_id, user_token_id, scope, parent_id, expires_at
                  FROM oauth_tokens
                 WHERE token_hash = ?
                   AND revoked_at IS NULL
                   AND expires_at > now()
                """,
            tokenHash);
    if (row == null) return Optional.empty();
    return Optional.of(
        new TokenLookup(
            row.get("id", UUID.class),
            row.get("kind", String.class),
            row.get("client_id", String.class),
            row.get("user_token_id", UUID.class),
            row.get("scope", String.class),
            row.get("parent_id", UUID.class),
            row.get("expires_at", OffsetDateTime.class)));
  }

  public void revokeToken(UUID id) {
    dsl.execute(
        "UPDATE oauth_tokens SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", id);
  }

  /** Lookup a token regardless of revoked/expired state — needed for refresh-reuse detection. */
  public Optional<TokenLookup> lookupAnyToken(String tokenHash) {
    Record row =
        dsl.fetchOne(
            """
                SELECT id, kind, client_id, user_token_id, scope, parent_id, expires_at
                  FROM oauth_tokens
                 WHERE token_hash = ?
                """,
            tokenHash);
    if (row == null) return Optional.empty();
    return Optional.of(
        new TokenLookup(
            row.get("id", UUID.class),
            row.get("kind", String.class),
            row.get("client_id", String.class),
            row.get("user_token_id", UUID.class),
            row.get("scope", String.class),
            row.get("parent_id", UUID.class),
            row.get("expires_at", OffsetDateTime.class)));
  }

  /** Walk parent_id chain back to its root. Used for compromise-detection chain revoke. */
  public UUID findChainRoot(UUID tokenId) {
    UUID current = tokenId;
    while (true) {
      Record row = dsl.fetchOne("SELECT parent_id FROM oauth_tokens WHERE id = ?", current);
      if (row == null) return current;
      UUID parent = row.get("parent_id", UUID.class);
      if (parent == null) return current;
      current = parent;
    }
  }

  /**
   * Detect refresh-token reuse: if a token with the given parent has already issued a child,
   * treat the chain as compromised and revoke everything downstream from the original parent.
   */
  public void revokeChain(UUID rootId) {
    dsl.execute(
        """
            WITH RECURSIVE chain(id) AS (
                SELECT id FROM oauth_tokens WHERE id = ?
                UNION ALL
                SELECT t.id FROM oauth_tokens t JOIN chain c ON t.parent_id = c.id
            )
            UPDATE oauth_tokens SET revoked_at = now()
             WHERE id IN (SELECT id FROM chain) AND revoked_at IS NULL
            """,
        rootId);
  }

  // --- Records --------------------------------------------------------

  public record OAuthClient(
      String clientId,
      String clientName,
      List<String> redirectUris,
      List<String> grantTypes,
      List<String> responseTypes,
      String tokenEndpointAuthMethod,
      String scope,
      String clientUri,
      String logoUri,
      List<String> contacts,
      String softwareId,
      String softwareVersion,
      OffsetDateTime createdAt) {}

  public record AuthorizationCode(
      String clientId,
      String redirectUri,
      String scope,
      String codeChallenge,
      String codeChallengeMethod,
      UUID userTokenId) {}

  public record TokenLookup(
      UUID id,
      String kind,
      String clientId,
      UUID userTokenId,
      String scope,
      UUID parentId,
      OffsetDateTime expiresAt) {}
}
