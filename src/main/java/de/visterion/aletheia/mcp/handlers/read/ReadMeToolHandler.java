package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.OperatingGuideService;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Hand-rolled {@code read_me} read tool handler; serves the seeded operating guide. */
@Component
@Order(14)
public class ReadMeToolHandler implements ToolHandler {

  private final OperatingGuideService operatingGuideService;

  public ReadMeToolHandler(OperatingGuideService operatingGuideService) {
    this.operatingGuideService = operatingGuideService;
  }

  @Override
  public String name() {
    return "read_me";
  }

  @Override
  public String description() {
    return "The Aletheia operating guide: how to work with this register, what counts as a"
        + " proposal versus a decision, and how to keep merchant identity distinct. Read it once"
        + " per session when you need the rules; wake_up returns the live state and the"
        + " customer's preferences.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return operatingGuideService.operatingGuide();
  }
}
