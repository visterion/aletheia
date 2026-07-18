package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.UnmatchedRecurringSort;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code list_unmatched_recurring} read tool handler; delegates to {@link
 * ReadTools#listUnmatchedRecurring(UnmatchedRecurringSort, Integer)}.
 */
@Component
@Order(9)
public class ListUnmatchedRecurringToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public ListUnmatchedRecurringToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "list_unmatched_recurring";
  }

  @Override
  public String description() {
    return "Recurring debits without a documented contract (TP1 contract grain, spec §5 M3):"
        + " UNION of (1) an unlinked mandate contract -- a contracts row whose"
        + " hivemem_cell_id is not yet set, with its recurring series, and (2) a"
        + " mandate-less auto series -- a recurring row with no contract_id at all (never"
        + " carried a mandate_id). contractId distinguishes the two shapes (set for (1),"
        + " null for (2)).";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalEnumString("sort", "annual_cost_desc, optional", "annual_cost_desc")
        .optionalInteger("limit", "max rows, optional")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    UnmatchedRecurringSort sort =
        ArgumentParser.optionalEnum(arguments, "sort", UnmatchedRecurringSort.class);
    Integer limit = ArgumentParser.optionalInteger(arguments, "limit");
    return readTools.listUnmatchedRecurring(sort, limit);
  }
}
