package de.visterion.aletheia.mcp.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolRegistry;
import de.visterion.aletheia.mcp.WriteTools;
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
import de.visterion.aletheia.mcp.handlers.write.ClassifyCounterpartyToolHandler;
import de.visterion.aletheia.mcp.handlers.write.ConfirmCounterpartyToolHandler;
import de.visterion.aletheia.mcp.handlers.write.CreateTagRuleToolHandler;
import de.visterion.aletheia.mcp.handlers.write.DeleteTagRuleToolHandler;
import de.visterion.aletheia.mcp.handlers.write.DismissCounterpartyToolHandler;
import de.visterion.aletheia.mcp.handlers.write.LinkContractToolHandler;
import de.visterion.aletheia.mcp.handlers.write.MarkRecurringToolHandler;
import de.visterion.aletheia.mcp.handlers.write.ReattributeTransactionToolHandler;
import de.visterion.aletheia.mcp.handlers.write.SetTagRuleEnabledToolHandler;
import de.visterion.aletheia.mcp.handlers.write.SplitTransactionToolHandler;
import de.visterion.aletheia.mcp.handlers.write.UpdatePreferencesToolHandler;
import de.visterion.aletheia.mcp.transport.McpResponse.McpTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Schema/registration contract for the 11 hand-rolled write tool handlers (Task 8), layered on
 * top of Task 7's 12 read handlers: {@code WRITER} must see all 23, {@code READER} only the 12
 * read tools; the array-of-objects schemas ({@code reattribute_transaction.refs}, {@code
 * split_transaction.tx}, {@code create_tag_rule.conditions}) must match the captured contract
 * shape.
 */
class WriteToolSchemasTest {

  private static final List<String> READ_NAMES =
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

  private static final List<String> WRITE_NAMES =
      List.of(
          "update_preferences",
          "classify_counterparty",
          "mark_recurring",
          "confirm_counterparty",
          "link_contract",
          "dismiss_counterparty",
          "reattribute_transaction",
          "split_transaction",
          "create_tag_rule",
          "set_tag_rule_enabled",
          "delete_tag_rule");

  private final ReadTools readTools = Mockito.mock(ReadTools.class);
  private final WriteTools writeTools = Mockito.mock(WriteTools.class);

  private final List<ToolHandler> readHandlers =
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

  private final List<ToolHandler> writeHandlers =
      List.of(
          new UpdatePreferencesToolHandler(writeTools),
          new ClassifyCounterpartyToolHandler(writeTools),
          new MarkRecurringToolHandler(writeTools),
          new ConfirmCounterpartyToolHandler(writeTools),
          new LinkContractToolHandler(writeTools),
          new DismissCounterpartyToolHandler(writeTools),
          new ReattributeTransactionToolHandler(writeTools),
          new SplitTransactionToolHandler(writeTools),
          new CreateTagRuleToolHandler(writeTools),
          new SetTagRuleEnabledToolHandler(writeTools),
          new DeleteTagRuleToolHandler(writeTools));

  private final List<ToolHandler> allHandlers =
      java.util.stream.Stream.concat(readHandlers.stream(), writeHandlers.stream()).toList();

  @Test
  void everyWriteHandlerNameMatchesExpectedWriteToolsAnnotation() {
    assertThat(writeHandlers).extracting(ToolHandler::name).containsExactlyInAnyOrderElementsOf(WRITE_NAMES);
  }

  @Test
  void visibleToolsForWriterReturnsAllTwentyThree() {
    ToolRegistry registry = new ToolRegistry(allHandlers, new ToolPermissionService());

    List<McpTool> visible = registry.visibleTools(AuthRole.WRITER);

    assertThat(visible).hasSize(23);
    List<String> expected =
        java.util.stream.Stream.concat(READ_NAMES.stream(), WRITE_NAMES.stream()).toList();
    assertThat(visible).extracting(McpTool::name).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void visibleToolsForReaderReturnsOnlyTheTwelveReadTools() {
    ToolRegistry registry = new ToolRegistry(allHandlers, new ToolPermissionService());

    List<McpTool> visible = registry.visibleTools(AuthRole.READER);

    assertThat(visible).hasSize(12);
    assertThat(visible).extracting(McpTool::name).containsExactlyInAnyOrderElementsOf(READ_NAMES);
  }

  @Test
  @SuppressWarnings("unchecked")
  void reattributeTransactionRefsIsArrayOfObjectsWithRequiredItemFields() {
    ToolHandler handler = byName("reattribute_transaction");

    Map<String, Object> schema = handler.inputSchema();
    assertThat(schema.get("required")).isEqualTo(List.of("refs"));

    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    Map<String, Object> refs = (Map<String, Object>) properties.get("refs");
    assertThat(refs.get("type")).isEqualTo("array");

    Map<String, Object> items = (Map<String, Object>) refs.get("items");
    assertThat(items.get("type")).isEqualTo("object");
    assertThat(items.get("additionalProperties")).isEqualTo(Boolean.FALSE);
    assertThat(items.get("required")).isEqualTo(List.of("contentHash", "occurrenceIndex"));

    Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
    assertThat(itemProperties.keySet()).containsExactlyInAnyOrder("contentHash", "occurrenceIndex");
  }

  @Test
  @SuppressWarnings("unchecked")
  void splitTransactionTxIsRequiredObject() {
    ToolHandler handler = byName("split_transaction");

    Map<String, Object> schema = handler.inputSchema();
    assertThat((List<String>) schema.get("required")).contains("tx");

    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    Map<String, Object> tx = (Map<String, Object>) properties.get("tx");
    assertThat(tx.get("type")).isEqualTo("object");
    assertThat(tx.get("required")).isEqualTo(List.of("contentHash", "occurrenceIndex"));
    assertThat(tx.get("additionalProperties")).isEqualTo(Boolean.FALSE);
  }

  @Test
  @SuppressWarnings("unchecked")
  void createTagRuleConditionsItemsCarryEnumOnFieldAndOp() {
    ToolHandler handler = byName("create_tag_rule");

    Map<String, Object> schema = handler.inputSchema();
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    Map<String, Object> conditions = (Map<String, Object>) properties.get("conditions");
    Map<String, Object> items = (Map<String, Object>) conditions.get("items");
    Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");

    Map<String, Object> field = (Map<String, Object>) itemProperties.get("field");
    assertThat(field.get("type")).isEqualTo("string");
    assertThat((List<String>) field.get("enum"))
        .containsExactlyInAnyOrder(
            "remittance_info", "counterparty_name", "creditor_id", "counterparty_iban", "direction");

    Map<String, Object> op = (Map<String, Object>) itemProperties.get("op");
    assertThat(op.get("type")).isEqualTo("string");
    assertThat((List<String>) op.get("enum")).containsExactlyInAnyOrder("contains", "equals");
  }

  @Test
  void noSchemaUsesFormatOrRef() {
    for (ToolHandler handler : writeHandlers) {
      String json = handler.inputSchema().toString();
      assertThat(json).as(handler.name() + " schema").doesNotContain("\"format\"").doesNotContain("$ref");
    }
  }

  private ToolHandler byName(String name) {
    return allHandlers.stream().filter(h -> h.name().equals(name)).findFirst().orElseThrow();
  }
}
