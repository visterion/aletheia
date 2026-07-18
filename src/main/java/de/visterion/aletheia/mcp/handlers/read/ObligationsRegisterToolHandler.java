package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code obligations_register} read tool handler; delegates to {@link
 * ReadTools#obligationsRegister()}.
 */
@Component
@Order(3)
public class ObligationsRegisterToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public ObligationsRegisterToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "obligations_register";
  }

  @Override
  public String description() {
    return "The documented obligations register: confirmed contracts (TP1 contract grain -- one"
        + " row per confirmed contracts row, so a counterparty with two confirmed"
        + " contracts, e.g. two insurance policies, produces two rows) with annual cost,"
        + " tags and contract-link status, ordered by annual cost, plus the total. Each"
        + " row's annual cost is scoped to its OWN contract -- never the counterparty's"
        + " combined debit. All debit/annual cost figures are derived from the logical"
        + " transaction view (NOT EXISTS on split_parent_* excludes superseded parents)."
        + " Excludes counterparties tagged with confirmed nature:zahlungsdienst; auto tags"
        + " do not exclude.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.obligationsRegister();
  }
}
