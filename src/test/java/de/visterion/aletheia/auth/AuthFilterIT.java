package de.visterion.aletheia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * Verifies that {@link AuthFilter} protects {@code /mcp} with a bearer token and rejects
 * unauthenticated requests with 401 (not a 302 redirect — there is no admin web UI in Aletheia).
 *
 * <p>Drives a real embedded server (rather than MockMvc's standalone/webAppContext setup, which
 * does not run servlet {@code Filter} beans unless explicitly registered) so the actual {@link
 * AuthFilter} bean, wired exactly as in production, is exercised end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFilterIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir");
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE api_tokens RESTART IDENTITY CASCADE");
  }

  @Test
  void unauthenticatedPostToMcpIsRejectedWith401NotRedirect() throws Exception {
    HttpResponse<Void> response = send("POST", null);

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void unauthenticatedGetToMcpIsRejectedWith401NotRedirect() throws Exception {
    HttpResponse<Void> response = send("GET", null);

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void validBearerTokenPassesTheFilter() throws Exception {
    String plaintext = "test-plaintext-token-value";
    seedToken(plaintext, "reader");

    // The filter must let the request through — whatever the MCP transport itself does with
    // an empty body afterwards, it must not be the auth filter's 401.
    HttpResponse<Void> response = send("POST", plaintext);

    assertThat(response.statusCode()).isNotEqualTo(401);
  }

  private HttpResponse<Void> send(String method, String bearerToken) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mcp"))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
  }

  private void seedToken(String plaintext, String role) {
    String hash = sha256Hex(plaintext);
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        hash,
        "it-token-" + role,
        role);
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
