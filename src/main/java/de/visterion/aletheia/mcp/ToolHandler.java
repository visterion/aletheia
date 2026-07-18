package de.visterion.aletheia.mcp;

import de.visterion.aletheia.auth.AuthPrincipal;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Contract implemented by every MCP tool exposed by this server. */
public interface ToolHandler {

  String name();

  String description();

  /**
   * JSON Schema describing the arguments this tool accepts. Surfaced to MCP clients via {@code
   * tools/list} so they know parameter names and types up front instead of guessing.
   *
   * <p>Default implementation returns an empty schema for handlers that accept no arguments.
   * Handlers with arguments should override this and return a schema built via {@link
   * ToolInputSchema}.
   */
  default Map<String, Object> inputSchema() {
    return ToolInputSchema.empty();
  }

  Object call(AuthPrincipal principal, JsonNode arguments);
}
