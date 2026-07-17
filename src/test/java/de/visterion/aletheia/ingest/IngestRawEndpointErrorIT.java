package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/** The raw-body path enforces the same app-level size cap as the multipart path (413). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestRawEndpointErrorIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void smallLimit(DynamicPropertyRegistry registry) {
    // Tiny cap so we can exceed it with a small payload (no 32MB allocation in tests).
    registry.add("aletheia.ingest.max-file-size", () -> "1KB");
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-raw-error-it");
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;
  @Autowired private IngestProperties properties;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE api_tokens RESTART IDENTITY CASCADE");
  }

  @Test
  void oneByteOverMaxIs413() throws Exception {
    String writer = seedToken("writer");
    byte[] oversized = new byte[(int) properties.maxFileSize().toBytes() + 1];

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Authorization", "Bearer " + writer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
            .build();

    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(413);
  }

  private String seedToken(String role) {
    String plaintext = "it-raw-err-" + role + "-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-raw-err-token-" + role + "-" + UUID.randomUUID(),
        role);
    return plaintext;
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
