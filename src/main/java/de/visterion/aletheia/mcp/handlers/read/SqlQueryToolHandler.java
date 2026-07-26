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
    return "Read-only escape hatch: run an arbitrary SELECT against the register/evidence schema."
        + " Runs on a SELECT-only DB role; non-SELECT, stacked, and SELECT INTO statements"
        + " are rejected before execution. CTEs are supported (WITH ... SELECT). The connection"
        + " runs as a SELECT-only database role, which is what actually prevents writes; a"
        + " statement that tries to write fails at the database, not at the input check.";
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
