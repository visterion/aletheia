package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * The tag values already in use for one fixed dimension ({@code domain}, {@code nature}, or
 * {@code necessity}), sorted descending by usage count.
 */
public record TaxonomyDimension(String dimension, List<TaxonomyValue> values) {}
