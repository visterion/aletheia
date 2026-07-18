package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Hand-rolled {@code taxonomy} read tool handler; delegates to {@link ReadTools#taxonomy()}. */
@Component
@Order(2)
public class TaxonomyToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public TaxonomyToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "taxonomy";
  }

  @Override
  public String description() {
    return "The emergent tag vocabulary already in use, per dimension (domain|nature|necessity),"
        + " with counts -- reuse these values instead of inventing synonyms.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.taxonomy();
  }
}
