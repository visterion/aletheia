package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.util.List;

/**
 * The documented obligations register (spec §5, the project's target artifact): confirmed
 * recurring outgoing (DBIT) obligations, ordered by {@link ObligationRow#annualCost} descending,
 * plus the grand total.
 *
 * @param rows the included obligations, sorted by annual cost descending
 * @param totalAnnualCost the sum of {@code rows}' annual costs
 */
public record ObligationsRegister(List<ObligationRow> rows, BigDecimal totalAnnualCost) {}
