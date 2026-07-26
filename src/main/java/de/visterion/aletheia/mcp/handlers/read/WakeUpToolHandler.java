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
    return "Answers: what needs my attention right now, and what have I told Aletheia about how"
        + " I work? Call this FIRST, before any other action. Returns a live snapshot of the"
        + " current state (open reviews, opaque payment passthroughs, obligations) and this"
        + " customer's recorded preferences -- the operating guide itself lives in read_me."
        + " Follow any recorded preferences."
        + " Record durable preferences with update_preferences."
        + "\n\nKeywords: Verpflichtung, Einkommen, Konto";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.wakeUp();
  }
}
