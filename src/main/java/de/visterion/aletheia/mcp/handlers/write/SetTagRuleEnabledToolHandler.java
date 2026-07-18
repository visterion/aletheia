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
 * Hand-rolled {@code set_tag_rule_enabled} write tool handler; delegates to {@link
 * WriteTools#setTagRuleEnabled(Long, Boolean)}.
 */
@Component
@Order(22)
public class SetTagRuleEnabledToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public SetTagRuleEnabledToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "set_tag_rule_enabled";
  }

  @Override
  public String description() {
    return "Pause or resume a tag rule without deleting it.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("ruleId", "tag_rules.id")
        .requiredBoolean("enabled", "true = enabled, false = paused")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long ruleId = ArgumentParser.requiredLong(arguments, "ruleId");
    boolean enabled = ArgumentParser.requiredBoolean(arguments, "enabled");
    return writeTools.setTagRuleEnabled(ruleId, enabled);
  }
}
