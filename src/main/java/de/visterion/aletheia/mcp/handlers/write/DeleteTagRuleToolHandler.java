package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code delete_tag_rule} write tool handler; delegates to {@link
 * WriteTools#deleteTagRule(Long)}.
 */
@Component
@Order(23)
public class DeleteTagRuleToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public DeleteTagRuleToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "delete_tag_rule";
  }

  @Override
  public String description() {
    return "Delete a tag rule. Does NOT roll back tags it already applied (those are confirmed"
        + " decisions; adjust them with classify_counterparty).";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object().requiredLong("ruleId", "tag_rules.id").build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long ruleId = ArgumentParser.requiredLong(arguments, "ruleId");
    return writeTools.deleteTagRule(ruleId);
  }
}
