package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * What a paged read tool actually applied, and how much existed before it did.
 *
 * <p>{@code rowsTotal} is counted after {@code minAmount} but before {@code limit}/{@code offset},
 * so {@code rowsTotal > rowsReturned} is the caller's signal that more rows exist. A truncated
 * list that does not say it was truncated is the same defect class as an invisible date window --
 * it is what makes a caller publish a number that is quietly wrong.
 *
 * <p>Deliberately NOT annotated {@code @JsonInclude(NON_NULL)}, unlike the row records: here a
 * {@code null} {@code minAmount} is the informative answer to "what filter was applied", and an
 * absent key would leave the caller guessing. In a row, null means "not recorded"; in the meta, it
 * means "no filter".
 *
 * @param rowsTotal matching rows before paging, after {@code minAmount}
 * @param rowsReturned rows actually in this response
 * @param limit the limit that was applied (resolved, never null)
 * @param offset the offset that was applied (resolved, never null)
 * @param minAmount the amount filter that was applied, {@code null} if none
 */
public record ListPageMeta(
    long rowsTotal, int rowsReturned, int limit, int offset, BigDecimal minAmount) {}
