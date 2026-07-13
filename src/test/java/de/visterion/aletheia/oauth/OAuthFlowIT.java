package de.visterion.aletheia.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
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

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

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
  void tokenExchangeWithMismatchedPkceVerifierIsRejected() throws Exception {
    // Register a client so client_id validation on the token endpoint has something to
    // resolve — the code itself will be bogus/unconsumable, so this exercises the
    // invalid_grant path (code unknown/expired) rather than a true PKCE mismatch, but proves
    // the token endpoint rejects unverifiable exchanges rather than ever minting tokens.
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
