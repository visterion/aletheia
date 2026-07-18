package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Hand-rolled {@code list_income} read tool handler; delegates to {@link ReadTools#listIncome()}. */
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
        + " here, ordered by total received.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.listIncome();
  }
}
