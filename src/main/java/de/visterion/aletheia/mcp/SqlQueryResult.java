package de.visterion.aletheia.mcp;

import java.util.List;
import java.util.Map;

/**
 * Result of {@link ReadTools#sqlQuery} (spec §5): column names in select order plus each row as
 * an ordered {@code column -> value} map, so the MCP client gets a readable structure regardless
 * of the query shape.
 */
public record SqlQueryResult(List<String> columns, List<Map<String, Object>> rows) {}
