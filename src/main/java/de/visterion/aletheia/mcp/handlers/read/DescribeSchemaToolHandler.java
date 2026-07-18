package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code describe_schema} read tool handler; delegates to {@link
 * ReadTools#describeSchema()}.
 */
@Component
@Order(5)
public class DescribeSchemaToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public DescribeSchemaToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "describe_schema";
  }

  @Override
  public String description() {
    return "Structure of the register/evidence schema (tables, columns, types, keys) so sql_query"
        + " can be written without guessing. No data rows.";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.describeSchema();
  }
}
