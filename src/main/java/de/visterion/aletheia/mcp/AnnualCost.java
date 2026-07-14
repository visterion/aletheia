package de.visterion.aletheia.mcp;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Estimates annual cost for a counterparty (spec §5): {@code typical_amount * periods/year} for a
 * non-irregular recurring series, else the direction-split {@code debit_last_365d} (spec M2 --
 * NOT the direction-blind {@code spend_last_365d}, so a {@code CRDT} refund does not deflate the
 * estimate).
 */
public final class AnnualCost {

  /** {@code recurring.cadence} -> occurrences/year. */
  private static final Map<String, Integer> CADENCE_PERIODS_PER_YEAR =
      Map.of(
          "monthly", 12,
          "quarterly", 4,
          "half_yearly", 2,
          "yearly", 1);

  private AnnualCost() {}

  public static BigDecimal estimate(RecurringView recurring, CounterpartyEvidence evidence) {
    if (recurring != null && recurring.typicalAmount() != null) {
      Integer periodsPerYear = CADENCE_PERIODS_PER_YEAR.get(recurring.cadence());
      if (periodsPerYear != null) {
        return recurring.typicalAmount().multiply(BigDecimal.valueOf(periodsPerYear));
      }
    }
    return evidence != null && evidence.debitLast365d() != null
        ? evidence.debitLast365d()
        : BigDecimal.ZERO;
  }

  /**
   * Contract-grain estimate: {@code typical_amount * periods/year} for a non-irregular series,
   * else the caller-supplied per-contract {@code debit_last_365d} (spec review M1 — never the
   * counterparty's whole debit).
   */
  public static BigDecimal estimate(RecurringView recurring, BigDecimal debitFallback) {
    if (recurring != null && recurring.typicalAmount() != null) {
      Integer periodsPerYear = CADENCE_PERIODS_PER_YEAR.get(recurring.cadence());
      if (periodsPerYear != null) {
        return recurring.typicalAmount().multiply(BigDecimal.valueOf(periodsPerYear));
      }
    }
    return debitFallback != null ? debitFallback : BigDecimal.ZERO;
  }
}
