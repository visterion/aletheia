package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Error-path contract for {@code POST /ingest} (spec §7/§8): the app-level and multipart-level
 * size guards both surface as 413 {@code ProblemDetail}, an empty file part and a malformed
 * export both surface as 400, the 400 body never echoes banking content, and no failed upload
 * ever leaves a leftover working file in {@code incoming/}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestEndpointErrorIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  private static final String INGEST_DIR = "target/test-ingest-dir-error-it";

  @DynamicPropertySource
  static void ingestDir(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> INGEST_DIR);
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;
  @Autowired private IngestProperties properties;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  void cleanUp() throws Exception {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, transactions, imports, counterparties "
            + "RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE api_tokens RESTART IDENTITY CASCADE");
    deleteRecursively(properties.dir().resolve("incoming"));
    deleteRecursively(properties.dir().resolve("imported"));
  }

  @Test
  void oneByteOverAppLevelMaxFileSizeIs413() throws Exception {
    String writer = seedToken("writer");
    long limit = properties.maxFileSize().toBytes();
    byte[] oversized = new byte[(int) limit + 1];

    HttpResponse<String> response = upload(writer, "too-big.json", oversized);

    assertThat(response.statusCode()).isEqualTo(413);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("status").asInt()).isEqualTo(413);
    assertThat(incoming()).isEmpty();
  }

  @Test
  void overTheMultipartMaxRequestSizeIs413ViaHandler() throws Exception {
    String writer = seedToken("writer");
    // Comfortably over the 40MB multipart ceiling (spring.servlet.multipart.max-file-size),
    // which the servlet container's multipart parser enforces before the controller runs --
    // this is the MaxUploadSizeExceededException path handled by IngestExceptionHandler,
    // distinct from the app-level 413 raised inside the controller.
    byte[] oversized = new byte[41 * 1024 * 1024];

    HttpResponse<String> response = upload(writer, "way-too-big.json", oversized);

    assertThat(response.statusCode()).isEqualTo(413);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("status").asInt()).isEqualTo(413);
    assertThat(incoming()).isEmpty();
  }

  @Test
  void emptyFilePartIs400() throws Exception {
    String writer = seedToken("writer");

    HttpResponse<String> response = upload(writer, "empty.json", new byte[0]);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(incoming()).isEmpty();
  }

  @Test
  void malformedExportIs400WithSanitizedBody() throws Exception {
    String writer = seedToken("writer");
    String malformed = "not json at all, IBAN DE1234567890, amount 999.99, remittance PRIVATE-INFO";

    HttpResponse<String> response = upload(writer, "bad.json", malformed.getBytes(UTF_8));

    assertThat(response.statusCode()).isEqualTo(400);
    String lowerBody = response.body().toLowerCase(java.util.Locale.ROOT);
    assertThat(lowerBody).doesNotContain("de1234567890").doesNotContain("999.99").doesNotContain("private-info");
    assertThat(incoming()).isEmpty();
  }

  /**
   * Spec §8: an export containing a booking with an unparseable/over-precision amount must
   * surface as 400 (via {@link InvalidExportException}), not 500 -- see the wrap added at the
   * throw site in {@code IngestService.doIngest}.
   */
  @Test
  void invalidBookingAmountIs400NotServerError() throws Exception {
    String writer = seedToken("writer");
    String badAmountExport =
        "[{\"Id\":\"9201\",\"AcctId\":\"ACC1\",\"OwnrAcctIBAN\":\"DE00000000000000000001\","
            + "\"Amt\":\"12.345\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
            + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\",\"RmtdNm\":\"BAD AMOUNT CO\",\"RmtInf\":\"x\"}]";

    HttpResponse<String> response = upload(writer, "bad-amount.json", badAmountExport.getBytes(UTF_8));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(incoming()).isEmpty();
  }

  private HttpResponse<String> upload(String bearerToken, String fileName, byte[] content)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Authorization", "Bearer " + bearerToken)
            .header("Content-Type", "multipart/form-data; boundary=" + IngestEndpointIT.BOUNDARY)
            .POST(IngestEndpointIT.multipartBody(fileName, content))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private java.util.List<Path> incoming() throws Exception {
    Path dir = properties.dir().resolve("incoming");
    if (!Files.exists(dir)) {
      return java.util.List.of();
    }
    try (var stream = Files.list(dir)) {
      return stream.toList();
    }
  }

  private static void deleteRecursively(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  private String seedToken(String role) {
    String plaintext = "it-ingest-error-" + role + "-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-ingest-error-token-" + role + "-" + UUID.randomUUID(),
        role);
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
