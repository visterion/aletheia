package de.visterion.aletheia.mcp;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.transport.McpRequest;
import de.visterion.aletheia.mcp.transport.McpResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Hand-rolled MCP Streamable HTTP transport, mounted on {@code /mcp} (Task 10 cutover: Spring AI's
 * MCP server has been removed; this controller is now the sole implementation). Ported from
 * HiveMem's {@code com.hivemem.mcp.McpController}; capabilities are tools-only (no
 * resources/prompts/completions/logging).
 */
@RestController
public class McpController {

  private static final Logger log = LoggerFactory.getLogger(McpController.class);

  private static final String SESSION_HEADER = "Mcp-Session-Id";

  /**
   * MCP protocol versions this server can serve. 2025-06-18 is the latest (and default);
   * 2025-03-26 (Streamable HTTP, pre-batching-removal) is also compatible since this server never
   * implements JSON-RPC batching regardless of declared version. A client requesting one of these
   * gets it echoed back; anything else falls back to the latest.
   */
  private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");

  private static final String LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS.get(0);

  /**
   * Exact wake_up bootstrap instructions handed to the client in {@code initialize}'s result
   * (spec §5 of the wakeup/operating-guide design).
   */
  private static final String INSTRUCTIONS =
      "Before your first action, call the wake_up tool to load this customer's operating guide,"
          + " their preferences, and the current state. Follow the operating guide. When you"
          + " learn a durable customer preference, record it with update_preferences.";

  /** SSE emitter lifetime; bounds how long a dead client connection can linger. */
  private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ToolRegistry toolRegistry;
  private final ToolCallDispatcher toolCallDispatcher;

  public McpController(ToolRegistry toolRegistry, ToolCallDispatcher toolCallDispatcher) {
    this.toolRegistry = toolRegistry;
    this.toolCallDispatcher = toolCallDispatcher;
  }

  /**
   * SSE endpoint for server-initiated messages (MCP Streamable HTTP spec). This server never
   * sends server-initiated messages, but Claude Code requires this endpoint to exist and return
   * 200 with {@code text/event-stream}. The emitter stays open until the client disconnects or
   * the timeout elapses.
   */
  @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException ignored) {
      // client already disconnected
    }
    emitter.onTimeout(emitter::complete);
    return emitter;
  }

  /** Session termination (MCP Streamable HTTP spec). No-op for this stateless server. */
  @DeleteMapping(value = "/mcp")
  public ResponseEntity<Void> deleteSession() {
    return ResponseEntity.ok().build();
  }

  @PostMapping(value = "/mcp")
  public ResponseEntity<?> handle(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
    if (body != null && body.isArray()) {
      // Protocol 2025-03-26 permits a client to send a JSON-RPC batch (a top-level array). This
      // server never implements batching; reject it with a proper JSON-RPC error instead of
      // letting a raw Jackson bind failure surface.
      return ResponseEntity.ok(
          McpResponse.invalidRequest(null, "Invalid Request: JSON-RPC batching is not supported"));
    }

    McpRequest request;
    try {
      request = MAPPER.treeToValue(body, McpRequest.class);
    } catch (Exception e) {
      // A syntactically-valid JSON body that doesn't fit the McpRequest shape (e.g. wrong field
      // types) must not escape as a raw Jackson exception -> HTTP 500.
      log.warn("MCP request body failed to bind to McpRequest: {}", e.getMessage());
      return ResponseEntity.ok(McpResponse.invalidRequest(null, "Invalid Request: " + e.getMessage()));
    }

    log.info(
        "MCP request: method={} id={} accept={} content-type={}",
        request.method(),
        request.id(),
        servletRequest.getHeader("Accept"),
        servletRequest.getHeader("Content-Type"));

    AuthPrincipal principal =
        (AuthPrincipal) servletRequest.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal == null) {
      return ResponseEntity.ok(
          McpResponse.invalidRequest(request.id(), "Invalid Request: missing authenticated principal"));
    }

    String method = request.method();
    if (method == null || method.isBlank()) {
      return ResponseEntity.ok(McpResponse.invalidRequest(request.id(), "Invalid Request: missing method"));
    }

    // Notifications (no id) get 202 Accepted with no body per MCP Streamable HTTP spec.
    if (method.startsWith("notifications/")) {
      return ResponseEntity.accepted().build();
    }

    return switch (method) {
      case "initialize" ->
          ResponseEntity.ok()
              .header(SESSION_HEADER, UUID.randomUUID().toString())
              .body(
                  McpResponse.success(
                      request.id(),
                      Map.of(
                          "protocolVersion", negotiateProtocolVersion(request.params()),
                          "capabilities", Map.of("tools", Map.of()),
                          "serverInfo", Map.of("name", "aletheia", "version", "0.1.0"),
                          "instructions", INSTRUCTIONS)));
      case "ping" -> ResponseEntity.ok(McpResponse.success(request.id(), Map.of()));
      case "tools/list" ->
          ResponseEntity.ok(
              McpResponse.success(
                  request.id(), Map.of("tools", toolRegistry.visibleTools(principal.role()))));
      case "tools/call" ->
          ResponseEntity.ok(toolCallDispatcher.dispatch(principal, request.id(), request.params()));
      default -> ResponseEntity.ok(McpResponse.methodNotFound(request.id(), method));
    };
  }

  /**
   * Echoes the client's requested {@code protocolVersion} when this server supports it; otherwise
   * (missing, blank, or an unsupported version) falls back to the latest supported version.
   */
  private static String negotiateProtocolVersion(JsonNode params) {
    if (params == null || !params.hasNonNull("protocolVersion")) {
      return LATEST_PROTOCOL_VERSION;
    }
    String requested = params.get("protocolVersion").asText();
    return SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL_VERSION;
  }
}
