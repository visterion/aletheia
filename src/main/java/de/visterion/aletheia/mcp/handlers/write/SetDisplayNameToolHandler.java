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

/** Hand-rolled {@code set_display_name} write tool handler; delegates to {@link WriteTools#setDisplayName}. */
@Component
@Order(25)
public class SetDisplayNameToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public SetDisplayNameToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "set_display_name";
  }

  @Override
  public String description() {
    return "Set or clear a counterparty's manual display-name override. The override is a label"
        + " only -- it wins over the auto-derived name at read time but never changes identity"
        + " resolution or how splits route. Pass name=null (or omit) to clear it and revert to the"
        + " automatic most-frequent name. Rejects a folded (merged) counterparty -- set the label"
        + " on the canonical one. Example: set_display_name(counterpartyId=42, name=\"Corner Bakery\").";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("counterpartyId", "counterparties.id")
        .optionalString("name", "new display label, or null to clear the override")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long counterpartyId = ArgumentParser.requiredLong(arguments, "counterpartyId");
    String name = ArgumentParser.optionalText(arguments, "name");
    return writeTools.setDisplayName(counterpartyId, name);
  }
}
