package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
import de.visterion.aletheia.mcp.transport.McpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolCallDispatcherTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final ToolPermissionService permissionService = new ToolPermissionService();
  private final ToolRegistry registry = mock(ToolRegistry.class);
  private final ToolCallDispatcher dispatcher =
      new ToolCallDispatcher(registry, permissionService, mapper);

  private JsonNode params(String json) {
    return mapper.readTree(json);
  }

  @Test
  void deniedRoleReturnsIsErrorToolResultNotForbidden() {
    AuthPrincipal reader = new AuthPrincipal("t", AuthRole.READER);

    McpResponse response =
        dispatcher.dispatch(reader, 1, params("{\"name\":\"classify_counterparty\",\"arguments\":{}}"));

    assertThat(response.error()).isNull();
    assertThat(response.result()).isNotNull();
    Map<?, ?> result = (Map<?, ?>) response.result();
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(textOf(result)).contains("not permitted").contains("classify_counterparty");
  }

  @Test
  void successSerializesHandlerResultAsToolResultText() {
    AuthPrincipal writer = new AuthPrincipal("t", AuthRole.WRITER);
    ToolHandler handler = mock(ToolHandler.class);
    when(handler.name()).thenReturn("list_counterparties");
    when(handler.call(any(), any())).thenReturn(List.of(Map.of("k", "v")));
    when(registry.resolve("list_counterparties")).thenReturn(Optional.of(handler));

    McpResponse response =
        dispatcher.dispatch(writer, 1, params("{\"name\":\"list_counterparties\",\"arguments\":{}}"));

    assertThat(response.error()).isNull();
    Map<?, ?> result = (Map<?, ?>) response.result();
    assertThat(result.get("isError")).isNull();
    assertThat(textOf(result)).isEqualTo(mapper.writeValueAsString(List.of(Map.of("k", "v"))));
  }

  @Test
  void handlerThrowingIllegalArgumentExceptionIsIsErrorNotInvalidParams() {
    AuthPrincipal writer = new AuthPrincipal("t", AuthRole.WRITER);
    ToolHandler handler = mock(ToolHandler.class);
    when(handler.name()).thenReturn("split_transaction");
    when(handler.call(any(), any()))
        .thenThrow(new IllegalArgumentException("pass confirm=true"));
    when(registry.resolve("split_transaction")).thenReturn(Optional.of(handler));

    McpResponse response =
        dispatcher.dispatch(writer, 1, params("{\"name\":\"split_transaction\",\"arguments\":{}}"));

    assertThat(response.error()).isNull();
    Map<?, ?> result = (Map<?, ?>) response.result();
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(textOf(result)).isEqualTo("pass confirm=true");
  }

  @Test
  void handlerThrowingMcpArgumentExceptionMapsToInvalidParams() {
    AuthPrincipal writer = new AuthPrincipal("t", AuthRole.WRITER);
    ToolHandler handler = mock(ToolHandler.class);
    when(handler.name()).thenReturn("split_transaction");
    when(handler.call(any(), any())).thenThrow(new McpArgumentException("Missing x"));
    when(registry.resolve("split_transaction")).thenReturn(Optional.of(handler));

    McpResponse response =
        dispatcher.dispatch(writer, 1, params("{\"name\":\"split_transaction\",\"arguments\":{}}"));

    assertThat(response.error()).isNotNull();
    assertThat(response.error().code()).isEqualTo(-32602);
    assertThat(response.error().message()).isEqualTo("Missing x");
  }

  @Test
  void missingToolNameReturnsInvalidParams() {
    AuthPrincipal writer = new AuthPrincipal("t", AuthRole.WRITER);

    McpResponse response = dispatcher.dispatch(writer, 1, params("{\"arguments\":{}}"));

    assertThat(response.error()).isNotNull();
    assertThat(response.error().code()).isEqualTo(-32602);
  }

  @Test
  void unknownToolReturnsToolNotFound() {
    // Use a name that IS in the admin allow-list so denial doesn't short-circuit the
    // resolve() step this test is exercising.
    AuthPrincipal admin = new AuthPrincipal("t", AuthRole.ADMIN);
    when(registry.resolve("list_counterparties")).thenReturn(Optional.empty());

    McpResponse response =
        dispatcher.dispatch(admin, 1, params("{\"name\":\"list_counterparties\",\"arguments\":{}}"));

    assertThat(response.error()).isNotNull();
    assertThat(response.error().code()).isEqualTo(-32602);
  }

  @SuppressWarnings("unchecked")
  private static String textOf(Map<?, ?> result) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }
}
