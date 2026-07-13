package de.visterion.aletheia.mcp;

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
 */
@Configuration
public class McpToolConfig {

  @Bean
  public ToolCallbackProvider readToolCallbackProvider(ReadTools readTools) {
    return MethodToolCallbackProvider.builder().toolObjects(readTools).build();
  }
}
