package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnualCostTest {

  @Test
  void monthly_cadence_uses_typical_amount_times_twelve() {
    RecurringView r =
        new RecurringView(1L, "monthly", new BigDecimal("10.00"), null, null,
            LocalDate.now(), LocalDate.now(), 12, "auto", null);
    assertThat(AnnualCost.estimate(r, new BigDecimal("999.99")))
        .isEqualByComparingTo("120.00");
  }

  @Test
  void irregular_cadence_uses_the_perContract_debit_fallback_not_counterparty_total() {
    RecurringView r =
        new RecurringView(1L, "irregular", new BigDecimal("50.00"), null, null,
            LocalDate.now(), LocalDate.now(), 3, "auto", null);
    // fallback is the PER-CONTRACT debit_last_365d, passed by the caller
    assertThat(AnnualCost.estimate(r, new BigDecimal("150.00")))
        .isEqualByComparingTo("150.00");
  }

  @Test
  void null_recurring_uses_fallback() {
    assertThat(AnnualCost.estimate(null, new BigDecimal("42.00")))
        .isEqualByComparingTo("42.00");
  }
}
