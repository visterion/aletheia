package de.visterion.aletheia.mcp;

/** One emergent tag value already in use within a {@link TaxonomyDimension}, with its usage count. */
public record TaxonomyValue(String value, long count) {}
