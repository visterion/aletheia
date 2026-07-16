package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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
 * Drives the real {@code POST /ingest} endpoint end to end over HTTP (mirrors {@code
 * AuthFilterIT}/{@code ToolScopeEnforcementIT}): a WRITER-token upload must ingest, refresh the
 * substrate without a restart, and be idempotent on re-upload of identical content.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestEndpointIT {

  /** Shared multipart boundary used by every ingest endpoint IT via {@link #multipartBody}. */
  static final String BOUNDARY = "IngestEndpointITBoundary";

  private static final String SYNTHETIC_EXPORT =
      "[{\"Id\":\"9001\",\"AcctId\":\"ACC1\",\"OwnrAcctIBAN\":\"DE00000000000000000001\","
          + "\"Amt\":\"12.34\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
          + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
          + "\"RmtdNm\":\"ENDPOINT TEST INSURER\",\"RmtInf\":\"contract fee\","
          + "\"CdtrId\":\"DE00ENDPOINT0000000001\",\"MndtId\":\"MND-E1\",\"EndToEndId\":\"E2E-E1\"}]";

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-endpoint-it");
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
  void writerUploadImportsAndRefreshesWithoutRestart() throws Exception {
    String writerToken = seedToken("writer");

    HttpResponse<String> response = upload(writerToken, "girokonto.json", SYNTHETIC_EXPORT);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("rowsNew").asInt()).isGreaterThan(0);
    assertThat(body.get("fileAlreadyImported").asBoolean()).isFalse();

    assertThat(
            db.fetchOne(de.visterion.aletheia.jooq.Tables.IMPORTS)
                .get(de.visterion.aletheia.jooq.Tables.IMPORTS.FILE_NAME))
        .isEqualTo("girokonto.json");

    // MCP read reflects the new state without a restart.
    try (McpSyncClient client = connect(writerToken)) {
      client.initialize();
      CallToolResult result =
          client.callTool(new CallToolRequest("list_counterparties", Map.of()));
      assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
      assertThat(textOf(result)).contains("ENDPOINT TEST INSURER");
    }
  }

  @Test
  void reuploadIsIdempotent() throws Exception {
    String writerToken = seedToken("writer");
    upload(writerToken, "girokonto.json", SYNTHETIC_EXPORT);

    HttpResponse<String> second = upload(writerToken, "girokonto.json", SYNTHETIC_EXPORT);

    assertThat(second.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(second.body());
    assertThat(body.get("fileAlreadyImported").asBoolean()).isTrue();
    assertThat(body.get("rowsNew").asInt()).isZero();
    assertThat(body.get("importId").asLong()).isEqualTo(-1);

    assertThat(db.fetchCount(de.visterion.aletheia.jooq.Tables.IMPORTS)).isEqualTo(1);
    assertThat(db.fetchCount(de.visterion.aletheia.jooq.Tables.TRANSACTIONS)).isEqualTo(1);
  }

  private HttpResponse<String> upload(String bearerToken, String fileName, String content)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Authorization", "Bearer " + bearerToken)
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(multipartBody(fileName, content.getBytes(UTF_8)))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  static HttpRequest.BodyPublisher multipartBody(String fileName, byte[] content) {
    String header =
        "--"
            + BOUNDARY
            + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\""
            + fileName
            + "\"\r\n"
            + "Content-Type: application/json\r\n\r\n";
    String footer = "\r\n--" + BOUNDARY + "--\r\n";
    return HttpRequest.BodyPublishers.ofByteArrays(
        java.util.List.of(header.getBytes(UTF_8), content, footer.getBytes(UTF_8)));
  }

  private McpSyncClient connect(String bearerToken) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + bearerToken))
            .build();
    return McpClient.sync(transport).build();
  }

  private static String textOf(CallToolResult result) {
    return result.content().stream()
        .filter(TextContent.class::isInstance)
        .map(TextContent.class::cast)
        .map(TextContent::text)
        .reduce("", (a, b) -> a + b);
  }

  private String seedToken(String role) {
    String plaintext = "it-ingest-" + role + "-" + java.util.UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-ingest-token-" + role + "-" + java.util.UUID.randomUUID(),
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
