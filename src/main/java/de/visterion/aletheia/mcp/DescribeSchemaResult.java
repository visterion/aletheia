package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * The {@code describe_schema} response: the requested schema columns plus three canonical example
 * queries.
 *
 * <p>The examples exist because a single worked query teaches more than twenty rows of column
 * metadata, and because all three of the things a first-time caller gets wrong are invisible in
 * the column list alone.
 *
 * @param columns one entry per column of the requested tables
 * @param examples runnable SELECTs demonstrating the three easy-to-miss rules
 */
public record DescribeSchemaResult(List<SchemaColumn> columns, List<String> examples) {}
