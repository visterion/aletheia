package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * The shared parameter surface of the paged read tools ({@code list_income},
 * {@code obligations_register}).
 *
 * <p>This record exists so the two tools cannot drift apart again. The defect this slice fixes is
 * not "too few parameters" -- it is that tools of the same class behaved differently depending on
 * which slice last touched them, so the fix has to be a single definition of what each parameter
 * means rather than per-handler parameters.
 *
 * <p>{@code minAmount} deliberately has no default. A default that silently hides rows would be
 * the same invisible-behaviour defect this slice exists to remove; {@code limit} bounds the
 * payload instead, and it announces itself through {@link ListPageMeta}.
 *
 * @param limit max rows to return; {@code null} means {@link #DEFAULT_LIMIT}. Must be positive
 * @param offset rows to skip; {@code null} means 0. Must not be negative
 * @param minAmount inclusive lower bound on the tool's amount column; {@code null} means no filter
 * @param verbose {@code null} or {@code false} means the compact row shape
 */
public record ListParams(Integer limit, Integer offset, BigDecimal minAmount, Boolean verbose) {

  public static final int DEFAULT_LIMIT = 25;

  public ListParams {
    if (limit != null && limit <= 0) {
      throw new IllegalArgumentException("limit must be > 0, was " + limit);
    }
    if (offset != null && offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0, was " + offset);
    }
  }

  public int effectiveLimit() {
    return limit == null ? DEFAULT_LIMIT : limit;
  }

  public int effectiveOffset() {
    return offset == null ? 0 : offset;
  }

  public boolean effectiveVerbose() {
    return Boolean.TRUE.equals(verbose);
  }
}
