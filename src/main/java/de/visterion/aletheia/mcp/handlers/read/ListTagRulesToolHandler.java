package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code list_tag_rules} read tool handler; delegates to {@link
 * ReadTools#listTagRules()}.
 */
@Component
@Order(6)
public class ListTagRulesToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public ListTagRulesToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "list_tag_rules";
  }

  @Override
  public String description() {
    return "List all auto-tagging rules (enabled and paused), oldest first.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.listTagRules();
  }
}
