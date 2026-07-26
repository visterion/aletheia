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

/**
 * Hand-rolled {@code describe_schema} read tool handler; delegates to {@link
 * ReadTools#describeSchema(java.util.List)}.
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
    return "Answers: what tables and columns exist, so I can write my own SQL against this"
        + " register without guessing? Structure of the register/evidence schema (tables,"
        + " columns, types, keys) so sql_query"
        + " can be written without guessing, plus three runnable example queries. No data rows."
        + " Optional tables filters the column list to an exact, lowercase subset of the allowed"
        + " names; an unknown name fails with the allowed list rather than returning nothing."
        + "\n\nKeywords: Konto";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalStringList(
            "tables",
            "restrict the output to these tables (exact, lowercase names); omit for all of them")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.describeSchema(ArgumentParser.optionalTextList(arguments, "tables"));
  }
}
