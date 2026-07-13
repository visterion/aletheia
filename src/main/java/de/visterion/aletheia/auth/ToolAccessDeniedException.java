package de.visterion.aletheia.auth;

/**
 * Thrown by {@link ScopeEnforcingToolCallback} when the calling {@link AuthPrincipal}'s role is
 * not permitted (per {@link ToolPermissionService}) to invoke the wrapped tool. Spring AI's MCP
 * tool dispatch (see {@code McpToolUtils}) catches any exception thrown from {@code
 * ToolCallback.call} and turns it into an MCP {@code CallToolResult} with {@code isError=true}
 * and the exception message as the result text -- so this surfaces to the MCP client as a tool
 * execution error, not an uncaught 500.
 */
public class ToolAccessDeniedException extends RuntimeException {

  public ToolAccessDeniedException(String message) {
    super(message);
  }
}
