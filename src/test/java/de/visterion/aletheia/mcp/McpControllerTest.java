package de.visterion.aletheia.mcp;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.mcp.transport.McpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc test for {@link McpController}, standalone-configured with mocked {@link ToolRegistry}
 * and {@link ToolCallDispatcher}. AuthFilter never runs in a standalone setup, so the WRITER
 * {@link AuthPrincipal} is stamped onto every request via a {@link RequestPostProcessor} that
 * mirrors what {@link AuthFilter} does in production.
 */
class McpControllerTest {

  private ToolRegistry toolRegistry;
  private ToolCallDispatcher toolCallDispatcher;
  private MockMvc mockMvc;

  private static final AuthPrincipal WRITER_PRINCIPAL =
      new AuthPrincipal("test-writer", AuthRole.WRITER, UUID.randomUUID());

  @BeforeEach
  void setUp() {
    toolRegistry = org.mockito.Mockito.mock(ToolRegistry.class);
    toolCallDispatcher = org.mockito.Mockito.mock(ToolCallDispatcher.class);
    McpController controller = new McpController(toolRegistry, toolCallDispatcher);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  private static RequestPostProcessor withWriterPrincipal() {
    return req -> {
      req.setAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, WRITER_PRINCIPAL);
      return req;
    };
  }

  @Test
  void initializeReturnsToolsOnlyCapabilitiesAndSessionHeader() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                    """))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.result.capabilities.length()").value(1))
        .andExpect(jsonPath("$.result.capabilities.tools").exists())
        .andExpect(jsonPath("$.result.serverInfo.name").value("aletheia"))
        .andExpect(jsonPath("$.result.serverInfo.version").value("0.1.0"))
        .andExpect(
            jsonPath("$.result.instructions")
                .value(
                    "Before your first action, call the wake_up tool to load this customer's"
                        + " operating guide, their preferences, and the current state. Follow the"
                        + " operating guide. When you learn a durable customer preference, record"
                        + " it with update_preferences."))
        .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
        .andExpect(header().exists("Mcp-Session-Id"))
        .andExpect(header().string("Mcp-Session-Id", not(emptyOrNullString())));
  }

  @Test
  void initializeEchoesSupportedRequestedProtocolVersion() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"));
  }

  @Test
  void initializeWithUnsupportedProtocolVersionFallsBackToLatest() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));
  }

  @Test
  void initializeWithMissingProtocolVersionFallsBackToLatest() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":4,"method":"initialize","params":{}}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));
  }

  @Test
  void notificationsMethodReturns202WithEmptyBody() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));
  }

  @Test
  void pingReturnsEmptyResult() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":5,"method":"ping"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").isMap())
        .andExpect(jsonPath("$.result").isEmpty())
        .andExpect(jsonPath("$.error").doesNotExist());
  }

  @Test
  void toolsListDelegatesToRegistryWithPrincipalRole() throws Exception {
    when(toolRegistry.visibleTools(eq(AuthRole.WRITER))).thenReturn(List.of());

    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":6,"method":"tools/list"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.tools").isArray());

    verify(toolRegistry).visibleTools(AuthRole.WRITER);
  }

  @Test
  void toolsCallDelegatesToDispatcher() throws Exception {
    when(toolCallDispatcher.dispatch(eq(WRITER_PRINCIPAL), eq(7), any()))
        .thenReturn(McpResponse.success(7, "dispatched"));

    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"status","arguments":{}}}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("dispatched"));

    verify(toolCallDispatcher).dispatch(eq(WRITER_PRINCIPAL), eq(7), any());
  }

  @Test
  void unknownMethodReturnsMethodNotFound() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":8,"method":"does/not-exist"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32601));
  }

  @Test
  void jsonRpcBatchArrayBodyReturnsInvalidRequest() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    [{"jsonrpc":"2.0","id":1,"method":"ping"}]
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32600))
        .andExpect(jsonPath("$.error.message").value(startsWith("Invalid Request")));
  }

  @Test
  void malformedBodyReturnsInvalidRequestNotHttp500() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":9,"method":["not","a","string"]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32600));
  }

  @Test
  void blankMethodReturnsInvalidRequest() throws Exception {
    mockMvc
        .perform(
            post("/mcp-v2")
                .with(withWriterPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"jsonrpc":"2.0","id":10,"method":""}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32600));
  }

  @Test
  void getReturnsSseEventStream() throws Exception {
    mockMvc
        .perform(get("/mcp-v2").with(withWriterPrincipal()).accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(request().asyncStarted());
  }

  @Test
  void deleteIsNoOpReturningOk() throws Exception {
    mockMvc.perform(delete("/mcp-v2").with(withWriterPrincipal())).andExpect(status().isOk());
  }
}
