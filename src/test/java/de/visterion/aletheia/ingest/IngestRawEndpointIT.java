package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Raw-body (non-multipart) variant of POST /ingest: the request body IS the export file. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestRawEndpointIT {

  private static final String SYNTHETIC_EXPORT =
      "[{\"Id\":\"9301\",\"AcctId\":\"ACC1\",\"OwnrAcctIBAN\":\"DE00000000000000000001\","
          + "\"Amt\":\"7.77\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
          + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
          + "\"RmtdNm\":\"RAW TEST CO\",\"RmtInf\":\"raw body\",\"CdtrId\":\"DE00RAW0000000000001\"}]";

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-raw-it");
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, transactions, imports, counterparties "
            + "RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE api_tokens RESTART IDENTITY CASCADE");
  }

  @Test
  void rawJsonBodyImports() throws Exception {
    String writer = seedToken("writer");
    HttpResponse<String> response = raw(writer, "application/json", null, SYNTHETIC_EXPORT.getBytes(UTF_8));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("rowsNew").asInt()).isGreaterThan(0);
    assertThat(body.get("fileAlreadyImported").asBoolean()).isFalse();
  }

  @Test
  void xFilenameHeaderIsUsed() throws Exception {
    String writer = seedToken("writer");
    raw(writer, "application/json", "konto1.json", SYNTHETIC_EXPORT.getBytes(UTF_8));

    assertThat(
            db.fetchOne(de.visterion.aletheia.jooq.Tables.IMPORTS)
                .get(de.visterion.aletheia.jooq.Tables.IMPORTS.FILE_NAME))
        .isEqualTo("konto1.json");
  }

  @Test
  void defaultFilenameWhenNoHeader() throws Exception {
    String writer = seedToken("writer");
    raw(writer, "application/json", null, SYNTHETIC_EXPORT.getBytes(UTF_8));

    String fileName =
        db.fetchOne(de.visterion.aletheia.jooq.Tables.IMPORTS)
            .get(de.visterion.aletheia.jooq.Tables.IMPORTS.FILE_NAME);
    assertThat(fileName).matches("raw-\\d{8}-\\d{6}\\.json");
  }

  @Test
  void rawReuploadIsIdempotent() throws Exception {
    String writer = seedToken("writer");
    raw(writer, "application/json", "konto1.json", SYNTHETIC_EXPORT.getBytes(UTF_8));
    HttpResponse<String> second = raw(writer, "application/json", "konto1.json", SYNTHETIC_EXPORT.getBytes(UTF_8));

    JsonNode body = mapper.readTree(second.body());
    assertThat(body.get("fileAlreadyImported").asBoolean()).isTrue();
    assertThat(db.fetchCount(de.visterion.aletheia.jooq.Tables.IMPORTS)).isEqualTo(1);
  }

  @Test
  void emptyRawBodyIs400() throws Exception {
    String writer = seedToken("writer");
    assertThat(raw(writer, "application/json", null, new byte[0]).statusCode()).isEqualTo(400);
  }

  @Test
  void octetStreamContentTypeAlsoRoutesToRaw() throws Exception {
    String writer = seedToken("writer");
    assertThat(
            raw(writer, "application/octet-stream", "konto1.json", SYNTHETIC_EXPORT.getBytes(UTF_8))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void jsonWithCharsetParameterRoutesToRaw() throws Exception {
    String writer = seedToken("writer");
    assertThat(
            raw(writer, "application/json; charset=utf-8", null, SYNTHETIC_EXPORT.getBytes(UTF_8))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void missingContentTypeRoutesToRaw() throws Exception {
    String writer = seedToken("writer");
    // Absent Content-Type -> servlet treats as octet-stream -> raw handler (settles the
    // "no Content-Type" case explicitly; addresses adversarial review M3).
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Authorization", "Bearer " + writer)
            .POST(HttpRequest.BodyPublishers.ofByteArray(SYNTHETIC_EXPORT.getBytes(UTF_8)))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void xFilenamePathTraversalIsSanitized() throws Exception {
    String writer = seedToken("writer");
    raw(writer, "application/json", "../../etc/passwd", SYNTHETIC_EXPORT.getBytes(UTF_8));

    String fileName =
        db.fetchOne(de.visterion.aletheia.jooq.Tables.IMPORTS)
            .get(de.visterion.aletheia.jooq.Tables.IMPORTS.FILE_NAME);
    assertThat(fileName).doesNotContain("/").doesNotContain("..");
  }

  @Test
  void readerRawBodyIsForbidden() throws Exception {
    String reader = seedToken("reader");
    assertThat(raw(reader, "application/json", null, SYNTHETIC_EXPORT.getBytes(UTF_8)).statusCode())
        .isEqualTo(403);
  }

  private HttpResponse<String> raw(String bearer, String contentType, String fileNameHeader, byte[] body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (fileNameHeader != null) {
      builder.header("X-Filename", fileNameHeader);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String seedToken(String role) {
    String plaintext = "it-raw-" + role + "-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-raw-token-" + role + "-" + UUID.randomUUID(),
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
