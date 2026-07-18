package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.substrate.CounterpartyResolver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
 * Task 9 parity gate: drives BOTH the hand-rolled {@code /mcp-v2} endpoint ({@link McpController})
 * and the still-live Spring AI {@code /mcp} endpoint over real HTTP with a genuine MCP SDK client
 * (mirrors {@code ToolScopeEnforcementIT}/{@code IngestEndpointIT}), and asserts they are
 * equivalent modulo the one intended difference (tools-only capabilities on {@code /mcp-v2}).
 *
 * <p>Using the live Spring AI output as the parity oracle avoids a circular "handlers produce
 * what handlers produce" golden-file test and covers all 23 tools without hand-authoring a golden
 * schema file that could silently drift from the real Spring AI contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-mcp-endpoint-it");
  }

  @LocalServerPort private int port;
  @Autowired private DSLContext db;
  @Autowired private CounterpartyResolver resolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_history, counterparty_tags, "
            + "counterparties, transactions, imports RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE api_tokens RESTART IDENTITY CASCADE");
    // operating_guide's 'default' row is a process-wide singleton shared across all test classes
    // on the singleton Postgres container (see ToolScopeEnforcementIT) -- leave no residue.
    db.execute(
        "UPDATE operating_guide SET preferences_md='', preferences_updated_at=NULL, "
            + "preferences_updated_by=NULL WHERE scope='default'");
  }

  // --- (a) handshake ---

  @Test
  void handshakeNegotiatesLatestVersionAndAdvertisesToolsOnlyCapabilities() {
    String writerToken = seedToken("writer");

    try (McpSyncClient client = connect("/mcp-v2", writerToken)) {
      InitializeResult result = client.initialize();

      assertThat(result.protocolVersion()).isEqualTo("2025-06-18");
      ServerCapabilities caps = result.capabilities();
      assertThat(caps.tools()).isNotNull();
      assertThat(caps.resources()).isNull();
      assertThat(caps.prompts()).isNull();
      assertThat(caps.completions()).isNull();
      assertThat(caps.logging()).isNull();
    }
  }

  // --- (b) role-filtered list ---

  @Test
  void toolsListIsRoleFilteredOnV2ButNotOnLegacyMcp() {
    String writerToken = seedToken("writer");
    String readerToken = seedToken("reader");

    try (McpSyncClient writer = connect("/mcp-v2", writerToken)) {
      writer.initialize();
      assertThat(writer.listTools().tools()).hasSize(23);
    }
    try (McpSyncClient reader = connect("/mcp-v2", readerToken)) {
      reader.initialize();
      assertThat(reader.listTools().tools()).hasSize(12);
    }

    // Spring AI's /mcp lists all 23 tools to every role -- do NOT assert role-filtering on it,
    // this is the known, intended difference the parity test exists to characterize.
    try (McpSyncClient legacyReader = connect("/mcp", readerToken)) {
      legacyReader.initialize();
      assertThat(legacyReader.listTools().tools()).hasSize(23);
    }
  }

  // --- (c) schema parity (differential) ---

  @Test
  void toolSchemasMatchLegacyEndpointModuloFormatKeys() {
    String writerToken = seedToken("writer");

    Map<String, Tool> v2Tools;
    Map<String, Tool> legacyTools;
    try (McpSyncClient v2 = connect("/mcp-v2", writerToken)) {
      v2.initialize();
      v2Tools = byName(v2.listTools().tools());
    }
    try (McpSyncClient legacy = connect("/mcp", writerToken)) {
      legacy.initialize();
      legacyTools = byName(legacy.listTools().tools());
    }

    assertThat(v2Tools.keySet()).isEqualTo(legacyTools.keySet());
    assertThat(v2Tools).hasSize(23);

    // Collect every mismatch across all 23 tools (rather than failing on the first) so a single
    // run reports the complete picture of any real parity gap.
    List<String> mismatches = new java.util.ArrayList<>();
    for (String name : v2Tools.keySet()) {
      Tool v2Tool = v2Tools.get(name);
      Tool legacyTool = legacyTools.get(name);

      if (!v2Tool.name().equals(legacyTool.name())) {
        mismatches.add(name + ": name differs (" + v2Tool.name() + " vs " + legacyTool.name() + ")");
      }
      // The top-level tool description (ToolHandler#description / @Tool(description=...)) IS
      // asserted byte-identical, per the brief's "names + descriptions must be identical".
      if (!java.util.Objects.equals(v2Tool.description(), legacyTool.description())) {
        mismatches.add(name + ": top-level description differs");
      }

      // Full structural + description parity: every property name/type/required-ness/enum-value
      // AND every property "description" (at every nesting level, including array-item and
      // nested-object sub-fields) must match exactly. Only the intended, cosmetic generator
      // differences are normalized away first: `format` keys (ToolInputSchema never emits them),
      // an explicit empty `required:[]` vs its absence, and `const` vs a one-element `enum`.
      Object normalizedV2 = normalizeStructural(v2Tool.inputSchema());
      Object normalizedLegacy = normalizeStructural(legacyTool.inputSchema());
      if (!normalizedV2.equals(normalizedLegacy)) {
        mismatches.add(
            name
                + ": inputSchema differs after normalization (types/required/enum-values/descriptions)"
                + "\n  v2:     "
                + normalizedV2
                + "\n  legacy: "
                + normalizedLegacy);
      }
    }

    assertThat(mismatches)
        .as(
            "structural + description parity mismatches (property names/types/required/"
                + "enum-values/descriptions at every level, top-level tool name/description)")
        .isEmpty();
  }

  /**
   * Normalizes a JSON-Schema fragment down to the parts that are load-bearing for parity with the
   * live Spring AI oracle: {@code type}, {@code properties} (by name, recursively normalized,
   * including each property's {@code description}), {@code required} (empty-array-normalized, see
   * {@link #normalizeEmptyRequired}), {@code additionalProperties}, {@code items}, and enum value
   * sets (a JSON-Schema {@code const} single-value field is folded into a one-element {@code enum}
   * so it compares equal to the hand-rolled encoding of the same constraint).
   *
   * <p>{@code description} is intentionally NOT stripped: a faithful hand-roll of the {@code
   * @ToolParam}-driven Spring AI contract copies every property description verbatim (task 9
   * review-fix), so full equality -- including descriptions at every nesting level -- is the
   * correct parity bar. The only normalized-away differences are genuinely cosmetic
   * generator-vs-hand-rolled encoding choices (an explicit {@code format} key, {@code
   * required:[]} vs its absence, {@code const} vs a one-element {@code enum}) that do not change
   * what a client can validly send or what a human reads.
   */
  private static Object normalizeStructural(Object node) {
    return normalizeConst(normalizeEmptyRequired(stripFormat(node)));
  }

  /**
   * Folds a JSON-Schema {@code const: X} constraint into {@code enum: [X]} (removing {@code
   * const}), so a single-allowed-value field compares equal regardless of which of the two
   * equivalent JSON-Schema keywords a generator chose. Discovered on {@code
   * list_unmatched_recurring}'s {@code sort} field: Spring AI's generator emits {@code const} for
   * a single-value {@code @ToolParam} enum, the hand-rolled {@link ToolInputSchema} always emits
   * {@code enum}. Both restrict the field to exactly the one value.
   */
  @SuppressWarnings("unchecked")
  private static Object normalizeConst(Object node) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = (String) entry.getKey();
        if ("const".equals(key)) {
          continue;
        }
        copy.put(key, normalizeConst(entry.getValue()));
      }
      if (map.containsKey("const") && !copy.containsKey("enum")) {
        copy.put("enum", List.of(map.get("const")));
      }
      return copy;
    }
    if (node instanceof List<?> list) {
      return list.stream().map(McpEndpointIT::normalizeConst).toList();
    }
    return node;
  }

  /**
   * Normalizes away a real-but-cosmetic parity gap discovered while writing this test (task
   * 9 finding, surfaced in the report, not silently fixed): Spring AI's {@code
   * JsonSchemaGenerator} emits an explicit {@code "required":[]} on a schema/nested-object with no
   * required fields, while the hand-rolled {@link ToolInputSchema} deliberately omits the key
   * entirely in that case (see its javadoc and {@code ToolInputSchemaTest}). Both mean exactly
   * "no field is required" -- semantically identical, just a presence-vs-absence difference on an
   * empty array -- so this normalization adds the empty array back wherever it is missing on an
   * object-typed schema fragment, before comparing. It does NOT touch a non-empty {@code
   * required} array on either side, so an actual required-field-list mismatch still fails.
   */
  @SuppressWarnings("unchecked")
  private static Object normalizeEmptyRequired(Object node) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        copy.put((String) entry.getKey(), normalizeEmptyRequired(entry.getValue()));
      }
      if ("object".equals(copy.get("type")) && !copy.containsKey("required")) {
        copy.put("required", List.of());
      }
      return copy;
    }
    if (node instanceof List<?> list) {
      return list.stream().map(McpEndpointIT::normalizeEmptyRequired).toList();
    }
    return node;
  }

  private static Map<String, Tool> byName(List<Tool> tools) {
    Map<String, Tool> byName = new LinkedHashMap<>();
    for (Tool tool : tools) {
      byName.put(tool.name(), tool);
    }
    return byName;
  }

  /**
   * Recursively strips every {@code "format"} key from a JSON-Schema fragment. Spring AI's {@code
   * JsonSchemaGenerator} emits {@code format: int64}/{@code int32} on integer-typed fields; the
   * hand-rolled {@link ToolInputSchema} deliberately emits none (javadoc on that class). This is
   * the one intended difference the differential parity check normalizes away -- anything else
   * that differs (e.g. a {@code required} array mismatch) must still fail the comparison.
   */
  @SuppressWarnings("unchecked")
  private static Object stripFormat(Object node) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = (String) entry.getKey();
        if ("format".equals(key)) {
          continue;
        }
        copy.put(key, stripFormat(entry.getValue()));
      }
      return copy;
    }
    if (node instanceof List<?> list) {
      return list.stream().map(McpEndpointIT::stripFormat).toList();
    }
    return node;
  }

  // --- (d)/(e)/(f) write/read tool-call round trips on /mcp-v2 ---

  @Test
  void aggregateWithWhereSelectorReturnsNonErrorResult() {
    String writerToken = seedToken("writer");
    long imp = importId();
    insertTxn(imp, "agg-1", LocalDate.of(2025, 3, 1), "10.00", "DBIT", "CDTR-AGG-IT", null, "Aggregate Where Co");
    resolver.run(null);

    try (McpSyncClient client = connect("/mcp-v2", writerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(
              new CallToolRequest(
                  "aggregate",
                  Map.of(
                      "dateFrom", "2025-01-01",
                      "dateTo", "2025-12-31",
                      "groupBy", "TOTAL",
                      "metric", "SUM",
                      "direction", "DBIT",
                      "where", Map.of("namePattern", "Aggregate Where Co"))));

      assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
      assertThat(textOf(result)).contains("10.00");
    }
  }

  @Test
  void reattributeTransactionWithArrayOfObjectsRefsRoundTrips() {
    String writerToken = seedToken("writer");
    long imp = importId();
    insertAdyen(imp, "reattr-it-1", "Zahlung Fizz Media IT");

    try (McpSyncClient client = connect("/mcp-v2", writerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(
              new CallToolRequest(
                  "reattribute_transaction",
                  Map.of(
                      "refs", List.of(Map.of("contentHash", "reattr-it-1", "occurrenceIndex", 0)),
                      "attributedName", "Fizz Media IT")));

      assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
      assertThat(textOf(result)).contains("Fizz Media IT");
    }

    assertThat(
            db.fetchValue(
                "SELECT attribution_source FROM transactions WHERE content_hash = 'reattr-it-1'"))
        .isEqualTo("manual");
    assertThat(
            db.fetchValue(
                "SELECT attributed_name FROM transactions WHERE content_hash = 'reattr-it-1'"))
        .isEqualTo("Fizz Media IT");
  }

  @Test
  void splitTransactionWithDecimalAllocationRoundTrips() {
    String writerToken = seedToken("writer");
    long imp = importId();
    insertTxn(imp, "split-it-1", LocalDate.of(2026, 1, 5), "4.10", "DBIT", "CDTR-SPLIT-IT", null, "Split Parent Co");

    try (McpSyncClient client = connect("/mcp-v2", writerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(
              new CallToolRequest(
                  "split_transaction",
                  Map.of(
                      "tx", Map.of("contentHash", "split-it-1", "occurrenceIndex", 0),
                      "allocations",
                          List.of(
                              Map.of(
                                  "amount", new BigDecimal("4.10"),
                                  "displayName", "Split Target IT")))));

      // Either a clean non-error result (the decimal-equality validation passed), or an explicit
      // validation-message error -- both prove the decimal parsed correctly end to end; a
      // McpArgumentException/transport-level failure would not.
      if (Boolean.TRUE.equals(result.isError())) {
        assertThat(textOf(result)).doesNotContain("Invalid");
      }
    }

    Integer childCount =
        (Integer)
            db.fetchValue(
                "SELECT count(*)::int FROM transactions WHERE split_parent_content_hash ="
                    + " 'split-it-1'");
    assertThat(childCount).isEqualTo(1);
  }

  // --- (g) result byte-equality (differential) ---

  @Test
  void wakeUpResultIsByteIdenticalAcrossEndpoints() {
    String writerToken = seedToken("writer");

    String v2Text = callAndGetText("/mcp-v2", writerToken, "wake_up", Map.of());
    String legacyText = callAndGetText("/mcp", writerToken, "wake_up", Map.of());

    assertThat(v2Text).isEqualTo(legacyText);
  }

  @Test
  void counterpartyTransactionsResultIsByteIdenticalAcrossEndpoints() {
    String writerToken = seedToken("writer");
    long imp = importId();
    insertTxn(
        imp,
        "byte-eq-1",
        LocalDate.of(2025, 6, 15),
        "42.50",
        "DBIT",
        "CDTR-BYTEEQ-IT",
        null,
        "Byte Equality Co");
    resolver.run(null);
    Long counterpartyId =
        (Long)
            db.fetchValue(
                "SELECT id FROM counterparties WHERE identity_type = 'creditor_id'"
                    + " AND identity_value = 'CDTR-BYTEEQ-IT'");

    Map<String, Object> args = Map.of("counterpartyId", counterpartyId);
    String v2Text = callAndGetText("/mcp-v2", writerToken, "counterparty_transactions", args);
    String legacyText = callAndGetText("/mcp", writerToken, "counterparty_transactions", args);

    assertThat(v2Text).isEqualTo(legacyText);
    assertThat(v2Text).contains("2025-06-15").contains("42.50");
  }

  @Test
  void sqlQueryResultIsByteIdenticalAcrossEndpoints() {
    String writerToken = seedToken("writer");

    Map<String, Object> args =
        Map.of(
            "sql",
            "SELECT 1::bigint AS int_col, 'text'::text AS text_col, 1.50::numeric AS num_col,"
                + " true AS bool_col, NULL::text AS null_col");
    String v2Text = callAndGetText("/mcp-v2", writerToken, "sql_query", args);
    String legacyText = callAndGetText("/mcp", writerToken, "sql_query", args);

    assertThat(v2Text).isEqualTo(legacyText);
  }

  @Test
  void aggregateWithNestedWhereResultIsByteIdenticalAcrossEndpoints() {
    String writerToken = seedToken("writer");
    long imp = importId();
    insertTxn(
        imp,
        "byte-eq-agg-1",
        LocalDate.of(2025, 4, 1),
        "17.25",
        "DBIT",
        "CDTR-BYTEEQ-AGG-IT",
        null,
        "Byte Equality Agg Co");
    resolver.run(null);

    Map<String, Object> args =
        Map.of(
            "dateFrom", "2025-01-01",
            "dateTo", "2025-12-31",
            "groupBy", "TOTAL",
            "metric", "SUM",
            "direction", "DBIT",
            "where", Map.of("namePattern", "Byte Equality Agg Co"));
    String v2Text = callAndGetText("/mcp-v2", writerToken, "aggregate", args);
    String legacyText = callAndGetText("/mcp", writerToken, "aggregate", args);

    assertThat(v2Text).isEqualTo(legacyText);
    assertThat(v2Text).contains("17.25");
  }

  private String callAndGetText(String endpoint, String token, String toolName, Map<String, Object> args) {
    try (McpSyncClient client = connect(endpoint, token)) {
      client.initialize();
      CallToolResult result = client.callTool(new CallToolRequest(toolName, args));
      assertThat(result.isError()).as("%s on %s", toolName, endpoint).isNotEqualTo(Boolean.TRUE);
      return textOf(result);
    }
  }

  // --- (h) reader denial ---

  @Test
  void readerCallingWriteToolOnV2IsDeniedWithoutTouchingTheDb() {
    String readerToken = seedToken("reader");
    long counterpartyId = seedCounterparty("CDTR-DENY-V2-IT");

    try (McpSyncClient client = connect("/mcp-v2", readerToken)) {
      client.initialize();

      CallToolResult result =
          client.callTool(
              new CallToolRequest(
                  "classify_counterparty",
                  Map.of(
                      "counterpartyIds", List.of(counterpartyId),
                      "tags", List.of(Map.of("dimension", "domain", "value", "telecom")),
                      "source", "auto",
                      "confirm", false)));

      assertThat(result.isError()).isTrue();
      assertThat(textOf(result)).contains("not permitted");
    }

    assertThat(
            db.fetchCount(
                de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS,
                de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId)))
        .isZero();
  }

  // --- fixtures / helpers ---

  private long importId() {
    return db.insertInto(de.visterion.aletheia.jooq.Tables.IMPORTS)
        .set(de.visterion.aletheia.jooq.Tables.IMPORTS.FILE_NAME, "mcp-endpoint-it.json")
        .set(de.visterion.aletheia.jooq.Tables.IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(de.visterion.aletheia.jooq.Tables.IMPORTS.ID)
        .fetchOne(de.visterion.aletheia.jooq.Tables.IMPORTS.ID);
  }

  private void insertTxn(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String direction,
      String creditorId,
      String iban,
      String name) {
    db.insertInto(de.visterion.aletheia.jooq.Tables.TRANSACTIONS)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.IMPORT_ID, importId)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.CURRENCY, "EUR")
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.DIRECTION, direction)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.COUNTERPARTY_IBAN, iban)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  private void insertAdyen(long importId, String contentHash, String remittance) {
    db.insertInto(de.visterion.aletheia.jooq.Tables.TRANSACTIONS)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.IMPORT_ID, importId)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.CURRENCY, "EUR")
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.DIRECTION, "DBIT")
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.CREDITOR_ID, "SYNTH-ADYEN-IT")
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.REMITTANCE_INFO, remittance)
        .set(de.visterion.aletheia.jooq.Tables.TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  private long seedCounterparty(String creditorId) {
    return db.fetchOne(
            "INSERT INTO counterparties (identity_type, identity_value, display_name) "
                + "VALUES ('creditor_id', ?, ?) RETURNING id",
            creditorId,
            "Endpoint IT Co")
        .get("id", Long.class);
  }

  private McpSyncClient connect(String endpoint, String bearerToken) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint(endpoint)
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
    String plaintext = "it-mcp-endpoint-" + role + "-" + UUID.randomUUID();
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, ?)",
        sha256Hex(plaintext),
        "it-mcp-endpoint-token-" + role + "-" + UUID.randomUUID(),
        role);
    return plaintext;
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
