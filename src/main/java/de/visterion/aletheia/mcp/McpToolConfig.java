package de.visterion.aletheia.mcp;

import de.visterion.aletheia.auth.ScopeEnforcingToolCallback;
import de.visterion.aletheia.auth.ToolPermissionService;
import java.util.Arrays;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Aletheia's {@code @Tool}-annotated beans as MCP tools (spec §5), so they are exposed
 * on the Spring AI MCP Streamable HTTP endpoint ({@code /mcp}, {@code spring.ai.mcp.server.*} in
 * {@code application.yml}). Spring AI's {@code ToolCallbackConverterAutoConfiguration} picks up
 * every {@link ToolCallbackProvider} bean in the context and converts its tools into MCP tool
 * specifications automatically -- no manual wiring beyond this bean is needed.
 *
 * <p>Every tool callback is wrapped in {@link ScopeEnforcingToolCallback} (spec §6, adversarial
 * round-3, Task 7 Part B): a caller whose {@code AuthRole} does not permit the tool is denied at
 * invocation time, for both read and write tools alike -- READER-only tools stay reachable for a
 * WRITER (its role permits the superset), but a WRITE tool is denied to a READER.
 */
@Configuration
public class McpToolConfig {

  @Bean
  public ToolCallbackProvider readToolCallbackProvider(
      ReadTools readTools, ToolPermissionService toolPermissionService) {
    return scopeEnforced(
        MethodToolCallbackProvider.builder().toolObjects(readTools).build(), toolPermissionService);
  }

  @Bean
  public ToolCallbackProvider writeToolCallbackProvider(
      WriteTools writeTools, ToolPermissionService toolPermissionService) {
    return scopeEnforced(
        MethodToolCallbackProvider.builder().toolObjects(writeTools).build(), toolPermissionService);
  }

  private static ToolCallbackProvider scopeEnforced(
      ToolCallbackProvider provider, ToolPermissionService toolPermissionService) {
    ToolCallback[] wrapped =
        Arrays.stream(provider.getToolCallbacks())
            .map(callback -> (ToolCallback) new ScopeEnforcingToolCallback(callback, toolPermissionService))
            .toArray(ToolCallback[]::new);
    return new StaticToolCallbackProvider(wrapped);
  }
}
