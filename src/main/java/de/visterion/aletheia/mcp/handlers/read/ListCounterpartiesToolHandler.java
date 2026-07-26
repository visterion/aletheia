package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.CounterpartyFilter;
import de.visterion.aletheia.mcp.CounterpartySort;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code list_counterparties} read tool handler; delegates to {@link
 * ReadTools#listCounterparties(CounterpartyFilter, CounterpartySort, String, Integer)}.
 */
@Component
@Order(7)
public class ListCounterpartiesToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public ListCounterpartiesToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "list_counterparties";
  }

  @Override
  public String description() {
    return "Answers: who have I paid or received money from, and what do I know about them"
        + " (tags, recurring series, contract link)? List counterparties with their evidence"
        + " aggregates, current tags, recurring series"
        + " and contract-link status. Evidence is computed over the logical view of"
        + " transactions (split parents excluded via NOT EXISTS on split_parent_*; only"
        + " children and unsplit originals contribute to counts/spend)."
        + " Optional namePattern filters case-insensitively on the effective display name"
        + " (LIKE-style -- % and _ act as wildcards); optional limit caps the number of"
        + " counterparties returned."
        + "\n\nKeywords: Abbuchung, Ausgaben, Rechnung";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalEnumString(
            "filter", "default all", "untagged", "unreviewed", "has_recurring", "all")
        .optionalEnumString("sort", "default spend_desc", "spend_desc", "recent")
        .optionalString(
            "namePattern",
            "case-insensitive substring match on the effective display name; % and _ are"
                + " LIKE wildcards")
        .optionalInteger("limit", "max counterparties to return (default: all); must be > 0")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    CounterpartyFilter filter = ArgumentParser.optionalEnum(arguments, "filter", CounterpartyFilter.class);
    CounterpartySort sort = ArgumentParser.optionalEnum(arguments, "sort", CounterpartySort.class);
    String namePattern = ArgumentParser.optionalText(arguments, "namePattern");
    Integer limit = ArgumentParser.optionalInteger(arguments, "limit");
    return readTools.listCounterparties(filter, sort, namePattern, limit);
  }
}
