package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
import de.visterion.aletheia.mcp.transport.McpResponse.McpTool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Unit tests for {@link ToolRegistry}: role-filtered visibility and name-based resolution. */
class ToolRegistryTest {

  // "taxonomy" is a real READ_TOOLS member; "link_contract" is a real WRITE_TOOLS member
  // (see ToolPermissionService).
  private final ToolHandler readStub = new StubHandler("taxonomy", "read stub");
  private final ToolHandler writeStub = new StubHandler("link_contract", "write stub");
  private final ToolPermissionService permissionService = new ToolPermissionService();

  @Test
  void visibleToolsForReaderContainsOnlyReadStub() {
    ToolRegistry registry = new ToolRegistry(List.of(readStub, writeStub), permissionService);

    List<McpTool> visible = registry.visibleTools(AuthRole.READER);

    assertThat(visible).extracting(McpTool::name).containsExactly("taxonomy");
  }

  @Test
  void visibleToolsForWriterContainsBothStubs() {
    ToolRegistry registry = new ToolRegistry(List.of(readStub, writeStub), permissionService);

    List<McpTool> visible = registry.visibleTools(AuthRole.WRITER);

    assertThat(visible).extracting(McpTool::name).containsExactlyInAnyOrder("taxonomy", "link_contract");
  }

  @Test
  void visibleToolsMapsToMcpToolWithNameDescriptionAndSchema() {
    ToolRegistry registry = new ToolRegistry(List.of(readStub, writeStub), permissionService);

    List<McpTool> visible = registry.visibleTools(AuthRole.READER);

    assertThat(visible).hasSize(1);
    McpTool tool = visible.get(0);
    assertThat(tool.name()).isEqualTo("taxonomy");
    assertThat(tool.description()).isEqualTo("read stub");
    assertThat(tool.inputSchema()).isEqualTo(ToolInputSchema.empty());
  }

  @Test
  void resolveFindsHandlerByName() {
    ToolRegistry registry = new ToolRegistry(List.of(readStub, writeStub), permissionService);

    Optional<ToolHandler> resolved = registry.resolve("link_contract");

    assertThat(resolved).isPresent();
    assertThat(resolved.get().name()).isEqualTo("link_contract");
  }

  @Test
  void resolveReturnsEmptyForUnknownName() {
    ToolRegistry registry = new ToolRegistry(List.of(readStub, writeStub), permissionService);

    assertThat(registry.resolve("does_not_exist")).isEmpty();
  }

  @Test
  void orderingIsStableAcrossCalls() {
    ToolRegistry registry = new ToolRegistry(List.of(readStub, writeStub), permissionService);

    List<String> firstCall =
        registry.visibleTools(AuthRole.WRITER).stream().map(McpTool::name).toList();
    List<String> secondCall =
        registry.visibleTools(AuthRole.WRITER).stream().map(McpTool::name).toList();

    assertThat(firstCall).isEqualTo(secondCall);
  }

  /** Minimal no-arg-taking {@link ToolHandler} test double. */
  private static final class StubHandler implements ToolHandler {

    private final String name;
    private final String description;

    private StubHandler(String name, String description) {
      this.name = name;
      this.description = description;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
      return Map.of("ok", true);
    }
  }
}
