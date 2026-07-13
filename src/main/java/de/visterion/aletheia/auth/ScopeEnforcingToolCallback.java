package de.visterion.aletheia.auth;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Wraps a Spring AI {@link ToolCallback} with a per-call {@link ToolPermissionService} check
 * (spec §6, adversarial round-3): a request authenticated with a role that does not permit the
 * wrapped tool is denied before the tool body ever runs.
 *
 * <p>Aletheia has no hand-rolled MCP dispatch controller to gate the way HiveMem's {@code
 * McpController.handleToolCall} does (it calls {@code toolPermissionService.isAllowed(...)}
 * before {@code toolRegistry.resolve(toolName).map(handler -> handler.call(...))}); Spring AI's
 * MCP server auto-configuration ({@code McpToolUtils.toSyncToolSpecification}) converts each
 * {@link ToolCallback} straight into an MCP tool handler. This decorator is the Spring-AI-idiomatic
 * equivalent of that same check, applied at the one seam every tool call passes through
 * regardless of transport: {@link ToolCallback#call(String, ToolContext)}.
 *
 * <p>The caller's {@link AuthPrincipal} is read from the {@link RequestAttributes} that {@link
 * AuthFilter} set on the current HTTP request -- the MCP webmvc transport dispatches tool calls
 * synchronously within the same servlet request, so {@link RequestContextHolder} is bound for the
 * duration of the call. If no principal is bound (should not happen -- {@link AuthFilter} already
 * gates {@code /mcp} as a whole) the call fails closed.
 *
 * <p>Denial throws {@link ToolAccessDeniedException} rather than returning a value: Spring AI's
 * MCP tool dispatch catches any exception from {@code call(...)} and converts it into an MCP
 * {@code CallToolResult} with {@code isError=true}, which is how an MCP client (and this project's
 * tests) observes the denial.
 */
public class ScopeEnforcingToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final ToolPermissionService toolPermissionService;

  public ScopeEnforcingToolCallback(ToolCallback delegate, ToolPermissionService toolPermissionService) {
    this.delegate = delegate;
    this.toolPermissionService = toolPermissionService;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    String toolName = delegate.getToolDefinition().name();
    AuthPrincipal principal = currentPrincipal();
    if (principal == null || !toolPermissionService.isAllowed(principal.role(), toolName)) {
      String role = principal == null ? "<none>" : principal.role().wireValue();
      throw new ToolAccessDeniedException(
          "role " + role + " is not permitted to call tool '" + toolName + "'");
    }
    return delegate.call(toolInput, toolContext);
  }

  private static AuthPrincipal currentPrincipal() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    Object principal =
        attributes.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return principal instanceof AuthPrincipal authPrincipal ? authPrincipal : null;
  }
}
