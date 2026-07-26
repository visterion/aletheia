package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Hand-rolled {@code sql_query} read tool handler; delegates to {@link ReadTools#sqlQuery(String)}. */
@Component
@Order(10)
public class SqlQueryToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public SqlQueryToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "sql_query";
  }

  @Override
  public String description() {
    return "Answers: any question over the data that the other tools don't already cover."
        + " Read-only escape hatch: run an arbitrary SELECT (CTEs supported, WITH ... SELECT)"
        + " against the register/evidence schema. Every statement runs read-only end to end;"
        + " any write is rejected, whether at input validation or at execution."
        + "\n\nKeywords: Konto, Ausgaben, Beleg";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object().requiredString("sql", "a single SELECT statement").build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    String sql = ArgumentParser.requiredText(arguments, "sql");
    return readTools.sqlQuery(sql);
  }
}
