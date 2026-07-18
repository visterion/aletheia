package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Hand-rolled {@code wake_up} read tool handler; delegates to {@link ReadTools#wakeUp()}. */
@Component
@Order(1)
public class WakeUpToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public WakeUpToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "wake_up";
  }

  @Override
  public String description() {
    return "Call this FIRST, before any other action. Returns this customer's Aletheia operating"
        + " guide, their recorded preferences, and a live snapshot of the current state"
        + " (open reviews, opaque payment passthroughs, obligations). Follow the guide."
        + " Record durable preferences with update_preferences.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.wakeUp();
  }
}
