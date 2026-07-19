package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
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
import de.visterion.aletheia.mcp.handlers.write.MergeCounterpartyToolHandler;
import de.visterion.aletheia.mcp.handlers.write.ReattributeTransactionToolHandler;
import de.visterion.aletheia.mcp.handlers.write.SetTagRuleEnabledToolHandler;
import de.visterion.aletheia.mcp.handlers.write.SplitTransactionToolHandler;
import de.visterion.aletheia.mcp.handlers.write.UpdatePreferencesToolHandler;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the wiring between the hand-rolled {@link ToolHandler} beans (the {@code mcp.handlers.*}
 * classes, registered in {@link ToolRegistry}) and {@link ToolPermissionService}'s
 * role-to-tool-name allow-list. A registered handler whose name is missing from the WRITER union
 * would be silently denied at runtime (fails closed) -- this test would catch that regression, and
 * an accidentally dropped or duplicated tool registration (guarded by the exact count of 23).
 *
 * <p>Rewritten for Task 10 (Spring AI removal): the previous version reflected on
 * {@code @Tool}-annotated methods on {@link ReadTools}/{@link WriteTools}; those classes no longer
 * carry MCP annotations (they are now plain beans the {@link ToolHandler}s delegate to), so
 * coverage is now asserted directly against every registered {@link ToolHandler}'s {@code name()},
 * which is what actually drives {@code tools/list}/{@code tools/call} post-cutover. Each handler is
 * constructed with a {@code null} {@link ReadTools}/{@link WriteTools} delegate -- {@code name()},
 * {@code description()}, and {@code inputSchema()} are pure (no field access), only {@code call()}
 * touches the delegate, and {@code call()} is not exercised here.
 */
class ToolPermissionCoverageTest {

  private static List<ToolHandler> allHandlers() {
    return List.of(
        new WakeUpToolHandler(null),
        new ListTagRulesToolHandler(null),
        new AggregateToolHandler(null),
        new ListCounterpartiesToolHandler(null),
        new GetReviewQueueToolHandler(null),
        new ListUnmatchedRecurringToolHandler(null),
        new CounterpartyTransactionsToolHandler(null),
        new TaxonomyToolHandler(null),
        new ObligationsRegisterToolHandler(null),
        new ListIncomeToolHandler(null),
        new SqlQueryToolHandler(null),
        new DescribeSchemaToolHandler(null),
        new UpdatePreferencesToolHandler(null),
        new ClassifyCounterpartyToolHandler(null),
        new MarkRecurringToolHandler(null),
        new ConfirmCounterpartyToolHandler(null),
        new LinkContractToolHandler(null),
        new DismissCounterpartyToolHandler(null),
        new ReattributeTransactionToolHandler(null),
        new SplitTransactionToolHandler(null),
        new CreateTagRuleToolHandler(null),
        new SetTagRuleEnabledToolHandler(null),
        new DeleteTagRuleToolHandler(null),
        new MergeCounterpartyToolHandler(null));
  }

  @Test
  void everyRegisteredToolIsCoveredByThePermissionServiceAndThereAreExactlyTwentyFour() {
    List<ToolHandler> handlers = allHandlers();
    assertThat(handlers).as("exactly 24 MCP tool handlers are registered").hasSize(24);

    Set<String> allToolNames = new HashSet<>();
    for (ToolHandler handler : handlers) {
      allToolNames.add(handler.name());
    }
    assertThat(allToolNames)
        .as("no tool handler name is duplicated")
        .hasSize(handlers.size());

    Set<String> allowedForWriter = new ToolPermissionService().allowedTools(AuthRole.WRITER);
    assertThat(allowedForWriter)
        .as("every registered tool handler name must be present in the WRITER permission union")
        .containsAll(allToolNames);
    assertThat(allowedForWriter).as("exactly 24 tools are visible to WRITER").hasSize(24);

    Set<String> allowedForReader = new ToolPermissionService().allowedTools(AuthRole.READER);
    assertThat(allowedForReader).as("exactly 12 tools are visible to READER").hasSize(12);
  }

  @Test
  void splitTransactionIsRegisteredAsWriteTool() {
    Set<String> allowedForWriter = new ToolPermissionService().allowedTools(AuthRole.WRITER);
    assertThat(allowedForWriter).contains("split_transaction");
    // readers must not get it (scope enforcement)
    Set<String> allowedForReader = new ToolPermissionService().allowedTools(AuthRole.READER);
    assertThat(allowedForReader).doesNotContain("split_transaction");
  }

  @Test
  void obligationsRegisterDescriptionDocumentsPaymentServiceExclusion() {
    ObligationsRegisterToolHandler handler = new ObligationsRegisterToolHandler(null);

    assertThat(handler.description())
        .contains(
            "Excludes counterparties tagged with confirmed nature:zahlungsdienst; "
                + "auto tags do not exclude.");
  }
}
