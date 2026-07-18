package de.visterion.aletheia.mcp.transport;

import tools.jackson.databind.JsonNode;

/** A JSON-RPC 2.0 request as sent by an MCP client. */
public record McpRequest(String jsonrpc, Object id, String method, JsonNode params) {}
