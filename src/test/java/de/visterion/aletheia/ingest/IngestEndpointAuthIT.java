package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Verifies the role gate on {@code POST /ingest}: no/garbage token -> 401 (via {@link
 * de.visterion.aletheia.auth.AuthFilter}); READER -> 403; WRITER/ADMIN -> 200; an OAuth
 * read-scoped access token minted over a WRITER-backed {@code api_tokens} row is capped to
 * READER by {@link de.visterion.aletheia.auth.AuthFilter#effectiveOauthRole} and so also gets 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestEndpointAuthIT {

  private static final String SYNTHETIC_EXPORT =
      "[{\"Id\":\"9101\",\"AcctId\":\"ACC1\",\"OwnrAcctIBAN\":\"DE00000000000000000001\","
          + "\"Amt\":\"5.00\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
          + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
          + "\"RmtdNm\":\"AUTH TEST CO\",\"RmtInf\":\"x\",\"CdtrId\":\"DE00AUTH00000000000001\"}]";

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-auth-it");
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, transactions, imports, counterparties "
            + "RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE oauth_tokens, oauth_authorization_codes, oauth_clients, api_tokens "
        + "RESTART IDENTITY CASCADE");
  }

  @Test
  void noTokenIsUnauthorized() throws Exception {
    assertThat(upload(null).statusCode()).isEqualTo(401);
  }

  @Test
  void garbageTokenIsUnauthorized() throws Exception {
    assertThat(upload("garbage-token-value").statusCode()).isEqualTo(401);
  }

  @Test
  void readerTokenIsForbidden() throws Exception {
    String reader = seedApiToken("reader");
    assertThat(upload(reader).statusCode()).isEqualTo(403);
  }

  @Test
  void writerTokenIsAllowed() throws Exception {
    String writer = seedApiToken("writer");
    assertThat(upload(writer).statusCode()).isEqualTo(200);
  }

  @Test
  void adminTokenIsAllowed() throws Exception {
    String admin = seedApiToken("admin");
    assertThat(upload(admin).statusCode()).isEqualTo(200);
  }

  @Test
  void oauthReadScopeOverWriterBackedTokenIsForbidden() throws Exception {
    UUID backingTokenId = seedApiTokenReturningId("writer");
    String clientId = seedOauthClient();
    String accessToken = seedOauthAccessToken(clientId, backingTokenId, "read");

    assertThat(upload(accessToken).statusCode()).isEqualTo(403);
  }

  private HttpResponse<Void> upload(String bearerToken) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Content-Type", "multipart/form-data; boundary=" + IngestEndpointIT.BOUNDARY)
            .POST(IngestEndpointIT.multipartBody("auth-test.json", SYNTHETIC_EXPORT.getBytes(UTF_8)));
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
  }

  private String seedApiToken(String role) {
    String plaintext = "it-ingest-auth-" + role + "-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-ingest-auth-token-" + role + "-" + UUID.randomUUID(),
        role);
    return plaintext;
  }

  private UUID seedApiTokenReturningId(String role) {
    String plaintext = "it-ingest-auth-backing-" + role + "-" + UUID.randomUUID();
    return db.fetchOne(
            "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?) RETURNING id",
            sha256Hex(plaintext),
            "it-ingest-auth-backing-token-" + role + "-" + UUID.randomUUID(),
            role)
        .get("id", UUID.class);
  }

  private String seedOauthClient() {
    String clientId = "it-ingest-client-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO oauth_clients (client_id, client_name, redirect_uris) "
            + "VALUES (?, ?, ARRAY['https://example.com/callback'])",
        clientId,
        "it-ingest-oauth-client");
    return clientId;
  }

  /** Mints an active {@code oauth_tokens} access-kind row bound to {@code backingTokenId}. */
  private String seedOauthAccessToken(String clientId, UUID backingTokenId, String scope) {
    String plaintext = "it-ingest-oauth-access-" + UUID.randomUUID();
    db.execute(
        """
            INSERT INTO oauth_tokens (kind, token_hash, client_id, user_token_id, scope, expires_at)
            VALUES ('access', ?, ?, ?, ?, ?::timestamptz)
            """,
        de.visterion.aletheia.oauth.TokenHasher.sha256(plaintext),
        clientId,
        backingTokenId,
        scope,
        OffsetDateTime.now().plusHours(1).toString());
    return plaintext;
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
