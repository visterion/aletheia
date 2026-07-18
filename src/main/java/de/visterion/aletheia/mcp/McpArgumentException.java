package de.visterion.aletheia.mcp;

/**
 * Thrown when {@code tools/call} arguments are missing a required field or a field has the wrong
 * type. Distinct from {@link IllegalArgumentException} so the JSON-RPC dispatcher can map only
 * this type to error code {@code -32602} (invalid params), while business validation failures
 * surface as an {@code isError} tool result instead.
 */
public class McpArgumentException extends RuntimeException {

  public McpArgumentException(String message) {
    super(message);
  }
}
