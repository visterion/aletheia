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
    return "Answers: what tag values exist for domain/nature/necessity, and which are actually in"
        + " use? `seed` is the canonical"
        + " start vocabulary -- try those values first. `values` is what is actually in use,"
        + " with counts. Introduce a new value only when nothing in seed fits; never invent a"
        + " synonym for a value that already exists."
        + "\n\nKeywords: Versicherung, Vertrag, Ausgaben";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.taxonomy();
  }
}
