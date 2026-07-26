package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ListParams;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code list_income} read tool handler; delegates to {@link
 * ReadTools#listIncome(ListParams)}.
 */
@Component
@Order(4)
public class ListIncomeToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public ListIncomeToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "list_income";
  }

  @Override
  public String description() {
    return "Incoming payments (CRDT): counterparties whose predominant direction is credit"
        + " (salary, transfers received) -- kept out of the obligations queue but available"
        + " here, ordered by total received. Returns {rows, meta} rather than a bare array;"
        + " meta reports the unpaged total so a compact 25-row default page never hides"
        + " truncation.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalInteger("limit", "max rows to return (default 25); must be > 0")
        .optionalInteger("offset", "rows to skip for paging (default 0); must be >= 0")
        .optionalNumber(
            "minAmount",
            "only counterparties whose all-time credit total is >= this value (default: no"
                + " filter)")
        .optionalBoolean(
            "verbose",
            "false (default): compact rows without identityType/firstSeen; true: the full row")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.listIncome(
        new ListParams(
            ArgumentParser.optionalInteger(arguments, "limit"),
            ArgumentParser.optionalInteger(arguments, "offset"),
            ArgumentParser.optionalDecimal(arguments, "minAmount"),
            ArgumentParser.optionalBoolean(arguments, "verbose")));
  }
}
