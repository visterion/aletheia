package de.visterion.aletheia.mcp;

import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
import de.visterion.aletheia.mcp.transport.McpResponse.McpTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

/**
 * Role-filtered registry of all {@link ToolHandler} beans wired into the application context.
 * Handlers are ordered via {@link AnnotationAwareOrderComparator} (honoring {@code @Order}) so
 * {@code tools/list} responses are stable across requests.
 */
@Service
public class ToolRegistry {

  private final List<ToolHandler> handlers;
  private final ToolPermissionService permissionService;

  public ToolRegistry(List<ToolHandler> handlers, ToolPermissionService permissionService) {
    ArrayList<ToolHandler> ordered = new ArrayList<>(handlers);
    AnnotationAwareOrderComparator.sort(ordered);
    this.handlers = List.copyOf(ordered);
    this.permissionService = permissionService;
  }

  public List<McpTool> visibleTools(AuthRole role) {
    Set<String> allowed = permissionService.allowedTools(role);
    return handlers.stream()
        .filter(handler -> allowed.contains(handler.name()))
        .map(handler -> new McpTool(handler.name(), handler.description(), handler.inputSchema()))
        .toList();
  }

  public Optional<ToolHandler> resolve(String name) {
    return handlers.stream().filter(handler -> handler.name().equals(name)).findFirst();
  }
}
