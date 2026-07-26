package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * The {@code {rows, meta}} envelope for a paged read tool.
 *
 * <p>{@code obligations_register} deliberately does NOT use this type: it carries a
 * {@code totalAnnualCost} that this envelope has no place for. Both embed the same
 * {@link ListPageMeta}, which is where the uniformity that matters lives.
 */
public record ListPage<T>(List<T> rows, ListPageMeta meta) {}
