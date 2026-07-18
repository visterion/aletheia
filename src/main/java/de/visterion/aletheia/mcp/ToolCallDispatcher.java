package de.visterion.aletheia.mcp;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.auth.ToolPermissionService;
import de.visterion.aletheia.mcp.transport.McpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Single dispatch path for {@code tools/call}: permission check, handler invocation, result
 * serialization, error mapping. Aletheia has no realm concept, so unlike HiveMem's dispatcher
 * this holds no ACL rewrite/filter layer.
 *
 * <p>Denial is reported as an {@code isError} tool result (matching {@link
 * de.visterion.aletheia.auth.ScopeEnforcingToolCallback}'s message shape), not a JSON-RPC
 * protocol error, so an MCP client sees the same "not permitted" text for the denial regardless
 * of which transport handled the call.
 */
@Component
public class ToolCallDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher.class);

  private final ToolRegistry toolRegistry;
  private final ToolPermissionService toolPermissionService;
  private final ObjectMapper mapper;

  public ToolCallDispatcher(
      ToolRegistry toolRegistry, ToolPermissionService toolPermissionService, ObjectMapper mapper) {
    this.toolRegistry = toolRegistry;
    this.toolPermissionService = toolPermissionService;
    this.mapper = mapper;
  }

  public McpResponse dispatch(AuthPrincipal principal, Object requestId, JsonNode params) {
    if (params == null || !params.hasNonNull("name")) {
      return McpResponse.invalidParams(requestId, "Missing tool name");
    }

    String toolName = params.get("name").asText();
    if (toolName.isBlank()) {
      return McpResponse.invalidParams(requestId, "Missing tool name");
    }

    if (!toolPermissionService.isAllowed(principal.role(), toolName)) {
      return McpResponse.toolExecutionError(
          requestId,
          "role " + principal.role().wireValue() + " is not permitted to call tool '" + toolName + "'");
    }

    return toolRegistry
        .resolve(toolName)
        .map(
            handler -> {
              JsonNode arguments = params.path("arguments");
              if (arguments == null || arguments.isMissingNode()) {
                arguments = MissingNode.getInstance();
              }
              try {
                Object result = handler.call(principal, arguments);
                String json = mapper.writeValueAsString(result);
                return McpResponse.toolResult(requestId, json);
              } catch (McpArgumentException e) {
                return McpResponse.invalidParams(requestId, messageOf(e));
              } catch (Exception e) {
                log.error("Tool call failed: {}", toolName, e);
                return McpResponse.toolExecutionError(requestId, messageOf(e));
              }
            })
        .orElseGet(() -> McpResponse.toolNotFound(requestId, toolName));
  }

  private static String messageOf(Exception e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }
}
