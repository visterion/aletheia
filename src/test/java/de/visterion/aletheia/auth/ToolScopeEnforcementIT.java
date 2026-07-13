package de.visterion.aletheia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * The security test for Task 7 Part B: proves the read/write scope split is actually enforced at
 * MCP tool-call dispatch, not just declared in {@link ToolPermissionService}. Drives the real
 * {@code /mcp} endpoint end to end with a genuine MCP client (same Streamable HTTP transport
 * claude.ai uses), the way {@code AuthFilterIT}/{@code OAuthFlowIT} drive real HTTP rather than
 * calling Java methods directly -- the thing under test here is the wiring between {@link
 * AuthFilter}, {@link ToolPermissionService} and Spring AI's MCP tool dispatch (mirrors HiveMem's
 * {@code McpControllerTest#postMcpToolsCallWithoutPermissionReturnsForbiddenError}, adapted to
 * Aletheia's Spring-AI-managed dispatch instead of a hand-rolled controller).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolScopeEnforcementIT {

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

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE api_tokens RESTART IDENTITY CASCADE");
  }

  @Test
  void readerTokenIsDeniedAWriteTool() {
    String readerToken = seedToken("reader");
    long counterpartyId = seedCounterparty("CDTR-DENY");

    try (McpSyncClient client = connect(readerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(
              new CallToolRequest(
                  "classify_counterparty",
                  Map.of(
                      "counterpartyId", counterpartyId,
                      "tags", List.of(Map.of("dimension", "domain", "value", "telecom")),
                      "source", "auto")));

      assertThat(result.isError()).isTrue();
      assertThat(textOf(result)).contains("not permitted").contains("classify_counterparty");
    }

    // The DB was never touched -- the permission check ran before the tool body.
    assertThat(
            db.fetchCount(
                de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS,
                de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId)))
        .isZero();
  }

  @Test
  void writerTokenIsAllowedTheSameWriteTool() {
    String writerToken = seedToken("writer");
    long counterpartyId = seedCounterparty("CDTR-ALLOW");

    try (McpSyncClient client = connect(writerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(
              new CallToolRequest(
                  "classify_counterparty",
                  Map.of(
                      "counterpartyId", counterpartyId,
                      "tags", List.of(Map.of("dimension", "domain", "value", "telecom")),
                      "source", "auto")));

      assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    }

    assertThat(
            db.fetchCount(
                de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS,
                de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId)))
        .isEqualTo(1);
  }

  @Test
  void readerTokenCanStillCallAReadTool() {
    String readerToken = seedToken("reader");
    seedCounterparty("CDTR-READ");

    try (McpSyncClient client = connect(readerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(new CallToolRequest("list_counterparties", Map.of()));

      assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    }
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
    String plaintext = "it-scope-" + role + "-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-scope-token-" + role,
        role);
    return plaintext;
  }

  private long seedCounterparty(String creditorId) {
    return db.fetchOne(
            "INSERT INTO counterparties (identity_type, identity_value, display_name) "
                + "VALUES ('creditor_id', ?, ?) RETURNING id",
            creditorId,
            "Scope Test Co")
        .get("id", Long.class);
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
