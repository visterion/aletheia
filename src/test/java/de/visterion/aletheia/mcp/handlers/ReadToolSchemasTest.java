package de.visterion.aletheia.mcp.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolRegistry;
import de.visterion.aletheia.mcp.handlers.read.AggregateToolHandler;
import de.visterion.aletheia.mcp.handlers.read.CounterpartyTransactionsToolHandler;
import de.visterion.aletheia.mcp.handlers.read.DescribeSchemaToolHandler;
import de.visterion.aletheia.mcp.handlers.read.GetReviewQueueToolHandler;
import de.visterion.aletheia.mcp.handlers.read.ListCounterpartiesToolHandler;
import de.visterion.aletheia.mcp.handlers.read.ListIncomeToolHandler;
import de.visterion.aletheia.mcp.handlers.read.ListTagRulesToolHandler;
import de.visterion.aletheia.mcp.handlers.read.ListUnmatchedRecurringToolHandler;
import de.visterion.aletheia.mcp.handlers.read.ObligationsRegisterToolHandler;
import de.visterion.aletheia.mcp.handlers.read.SqlQueryToolHandler;
import de.visterion.aletheia.mcp.handlers.read.TaxonomyToolHandler;
import de.visterion.aletheia.mcp.handlers.read.WakeUpToolHandler;
import de.visterion.aletheia.mcp.transport.McpResponse.McpTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Schema/registration contract for the 12 hand-rolled read tool handlers (Task 7): each handler's
 * {@code name()} must match {@code ReadTools}' current {@code @Tool} name exactly, all 12 must be
 * visible to {@link AuthRole#READER}, and {@code aggregate}'s schema must expose the documented
 * required fields plus the nested {@code where} selector.
 */
class ReadToolSchemasTest {

  private static final List<String> EXPECTED_NAMES =
      List.of(
          "wake_up",
          "taxonomy",
          "obligations_register",
          "list_income",
          "describe_schema",
          "list_tag_rules",
          "list_counterparties",
          "get_review_queue",
          "list_unmatched_recurring",
          "sql_query",
          "counterparty_transactions",
          "aggregate");

  private final ReadTools readTools = Mockito.mock(ReadTools.class);

  private final List<ToolHandler> handlers =
      List.of(
          new WakeUpToolHandler(readTools),
          new TaxonomyToolHandler(readTools),
          new ObligationsRegisterToolHandler(readTools),
          new ListIncomeToolHandler(readTools),
          new DescribeSchemaToolHandler(readTools),
          new ListTagRulesToolHandler(readTools),
          new ListCounterpartiesToolHandler(readTools),
          new GetReviewQueueToolHandler(readTools),
          new ListUnmatchedRecurringToolHandler(readTools),
          new SqlQueryToolHandler(readTools),
          new CounterpartyTransactionsToolHandler(readTools),
          new AggregateToolHandler(readTools));

  @Test
  void visibleToolsForReaderReturnsAllTwelve() {
    ToolRegistry registry = new ToolRegistry(handlers, new ToolPermissionService());

    List<McpTool> visible = registry.visibleTools(AuthRole.READER);

    assertThat(visible).hasSize(12);
    assertThat(visible).extracting(McpTool::name).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
  }

  @Test
  void everyHandlerNameMatchesExpectedReadToolsAnnotation() {
    assertThat(handlers).extracting(ToolHandler::name).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
  }

  @Test
  void aggregateSchemaHasRequiredFieldsAndNestedWhereAndCounterpartyIds() {
    ToolHandler aggregate =
        handlers.stream().filter(h -> h.name().equals("aggregate")).findFirst().orElseThrow();

    var schema = aggregate.inputSchema();

    assertThat(schema.get("required"))
        .isEqualTo(List.of("dateFrom", "dateTo", "groupBy", "metric", "direction"));

    @SuppressWarnings("unchecked")
    var properties = (java.util.Map<String, Object>) schema.get("properties");

    @SuppressWarnings("unchecked")
    var counterpartyIds = (java.util.Map<String, Object>) properties.get("counterpartyIds");
    assertThat(counterpartyIds.get("type")).isEqualTo("array");
    assertThat(counterpartyIds.get("items")).isEqualTo(java.util.Map.of("type", "integer"));

    @SuppressWarnings("unchecked")
    var where = (java.util.Map<String, Object>) properties.get("where");
    assertThat(where.get("type")).isEqualTo("object");
    @SuppressWarnings("unchecked")
    var whereProps = (java.util.Map<String, Object>) where.get("properties");
    assertThat(whereProps.keySet())
        .containsExactlyInAnyOrder(
            "domainIn",
            "natureIn",
            "minAnnualCost",
            "namePattern",
            "predominantDirection",
            "reviewed",
            "hasContract",
            "untagged");
  }

  @Test
  void noSchemaUsesFormatOrRef() {
    for (ToolHandler handler : handlers) {
      String json = handler.inputSchema().toString();
      assertThat(json).as(handler.name() + " schema").doesNotContain("\"format\"").doesNotContain("$ref");
    }
  }
}
