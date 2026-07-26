package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.util.List;

/**
 * The documented obligations register (spec §5, the project's target artifact): confirmed
 * recurring outgoing (DBIT) obligations, ordered by {@link ObligationRow#annualCost} descending,
 * plus the grand total.
 *
 * @param rows the included obligations, sorted by annual cost descending, then by contractId
 *     ascending as a tie-breaker
 * @param totalAnnualCost the sum of annual costs over ALL matching contracts -- computed after
 *     {@code minAmount} but before {@code limit}/{@code offset}, so the listed rows do NOT add up
 *     to it as soon as either paging parameter is set
 * @param meta what was applied and how many rows matched before paging
 */
public record ObligationsRegister(
    List<ObligationRow> rows, BigDecimal totalAnnualCost, ListPageMeta meta) {}
