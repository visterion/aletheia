package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * The tag vocabulary for one fixed dimension ({@code domain}, {@code nature}, or {@code necessity}).
 *
 * @param dimension the tag dimension
 * @param seed the canonical start vocabulary from {@link SeedVocabulary} -- try these values first
 * @param values the values actually in use, sorted descending by usage count; empty when the
 *     dimension carries no tags yet, never {@code null}
 */
public record TaxonomyDimension(String dimension, List<String> seed, List<TaxonomyValue> values) {}
