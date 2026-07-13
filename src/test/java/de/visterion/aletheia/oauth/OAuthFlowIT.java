package de.visterion.aletheia.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the OAuth 2.1 + PKCE + DCR flow ported from HiveMem: discovery metadata, Dynamic
 * Client Registration (RFC 7591), and PKCE enforcement at the token endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OAuthFlowIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void oauthProperties(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir");
    registry.add("aletheia.oauth.enabled", () -> "true");
    registry.add("aletheia.oauth.issuer", () -> "http://localhost:0");
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE oauth_tokens, oauth_authorization_codes, oauth_clients, api_tokens RESTART IDENTITY CASCADE");
  }

  @Test
  void discoveryMetadataAdvertisesBareScopes() throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/.well-known/oauth-authorization-server"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    JsonNode scopes = body.get("scopes_supported");
    assertThat(scopes).isNotNull();
    java.util.List<String> scopeValues = new java.util.ArrayList<>();
    scopes.forEach(n -> scopeValues.add(n.asText()));
    assertThat(scopeValues).contains("read", "write");
  }

  @Test
  void dynamicClientRegistrationRoundTrips() throws Exception {
    String requestBody =
        """
        {"client_name":"test-client","redirect_uris":["https://example.com/callback"]}
        """;
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("client_id").asText()).isNotBlank();
    assertThat(body.get("redirect_uris").get(0).asText()).isEqualTo("https://example.com/callback");
  }

  @Test
  void tokenExchangeWithUnknownCodeIsRejected() throws Exception {
    // The code itself is bogus/never issued, so codes.consume(code) returns empty before the
    // Pkce.verify branch is ever reached. This only proves the token endpoint rejects
    // unresolvable codes — see tokenExchangeWithGenuineCodeAndMismatchedVerifierIsRejected below
    // for the test that actually exercises PKCE mismatch.
    String clientId = registerClient();

    String codeVerifier = randomUrlSafe();
    String form =
        "grant_type=authorization_code"
            + "&code=bogus-code-never-issued"
            + "&client_id="
            + clientId
            + "&redirect_uri=https://example.com/callback"
            + "&code_verifier="
            + codeVerifier;

    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("error").asText()).isEqualTo("invalid_grant");
  }

  /**
   * Genuinely exercises the {@code Pkce.verify} branch in {@code TokenEndpointService.exchangeCode}.
   *
   * <p>The controller-level test-injection seam ({@code AuthorizationController.TEST_USER_TOKEN_ATTR})
   * only works for in-process MockMvc-style tests that can set a servlet request attribute before
   * dispatch; this suite drives a real embedded server over the network with a plain {@link
   * HttpClient} (same rationale as {@code AuthFilterIT}), so that seam is not reachable from here.
   * Instead we seed a real, unconsumed {@code oauth_authorization_codes} row directly via {@link
   * DSLContext} — same shape {@link AuthorizationCodeService#issue} would produce — with a known
   * {@code code_challenge}, then hit {@code /oauth/token} with a verifier that does not hash to
   * that challenge. This reaches {@code codes.consume(code)} successfully and then fails inside
   * {@code Pkce.verify}, so the assertion below is a genuine PKCE-mismatch rejection.
   */
  @Test
  void tokenExchangeWithGenuineCodeAndMismatchedVerifierIsRejected() throws Exception {
    String clientId = registerClient();
    UUID userTokenId = seedUserToken();
    String correctVerifier = randomUrlSafe();
    String challenge = Pkce.computeS256Challenge(correctVerifier);
    String code = seedAuthorizationCode(clientId, challenge, userTokenId);

    String wrongVerifier = randomUrlSafe();
    HttpResponse<String> response = exchangeToken(clientId, code, wrongVerifier);

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("error").asText()).isEqualTo("invalid_grant");
  }

  @Test
  void tokenExchangeWithGenuineCodeAndCorrectVerifierSucceeds() throws Exception {
    String clientId = registerClient();
    UUID userTokenId = seedUserToken();
    String correctVerifier = randomUrlSafe();
    String challenge = Pkce.computeS256Challenge(correctVerifier);
    String code = seedAuthorizationCode(clientId, challenge, userTokenId);

    HttpResponse<String> response = exchangeToken(clientId, code, correctVerifier);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("access_token").asText()).isNotBlank();
    assertThat(body.get("refresh_token").asText()).isNotBlank();
  }

  private HttpResponse<String> exchangeToken(String clientId, String code, String codeVerifier) throws Exception {
    String form =
        "grant_type=authorization_code"
            + "&code="
            + code
            + "&client_id="
            + clientId
            + "&redirect_uri=https://example.com/callback"
            + "&code_verifier="
            + codeVerifier;
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/oauth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private UUID seedUserToken() {
    return db.fetchOne(
            "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?) RETURNING id",
            randomUrlSafe(),
            "it-oauth-user-" + UUID.randomUUID(),
            "reader")
        .get("id", UUID.class);
  }

  /** Seeds a real, unconsumed authorization code row — same shape {@link
   * AuthorizationCodeService#issue} produces — with a known code_challenge, so the token endpoint
   * can genuinely consume it and reach {@code Pkce.verify}. */
  private String seedAuthorizationCode(String clientId, String codeChallenge, UUID userTokenId) {
    String code = randomUrlSafe();
    db.execute(
        """
        INSERT INTO oauth_authorization_codes
            (code_hash, client_id, redirect_uri, scope, code_challenge,
             code_challenge_method, user_token_id, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, now() + interval '10 minutes')
        """,
        TokenHasher.sha256(code),
        clientId,
        "https://example.com/callback",
        "read write",
        codeChallenge,
        "S256",
        userTokenId);
    return code;
  }

  private String registerClient() throws Exception {
    String requestBody =
        """
        {"client_name":"test-client","redirect_uris":["https://example.com/callback"]}
        """;
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(response.body()).get("client_id").asText();
  }

  private static String randomUrlSafe() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }
}
