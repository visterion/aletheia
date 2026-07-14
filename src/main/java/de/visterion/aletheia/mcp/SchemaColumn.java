package de.visterion.aletheia.mcp;

/**
 * One column of the register/evidence schema, as returned by {@link ReadTools#describeSchema()}.
 * Structure only -- no data rows.
 */
public record SchemaColumn(
    String table,
    String column,
    String type,
    boolean nullable,
    boolean primaryKey,
    boolean foreignKey,
    String description) {}
