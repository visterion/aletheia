package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.V_COUNTERPARTY_EVIDENCE;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@link CounterpartySelector} to the matching {@code counterparties.id}s. Shared
 * {@code where}-clause logic for the {@code aggregate} and batch {@code classify_counterparty}
 * tools (spec §5).
 */
@Component
public class CounterpartySelectorResolver {

  private final DSLContext db;

  public CounterpartySelectorResolver(DSLContext db) {
    this.db = db;
  }

  /**
   * Resolves the selector. A {@code null} selector returns every counterparty id. {@code
   * minAnnualCost} is applied in Java after fetching (it is not a column, spec §5).
   *
   * @throws IllegalArgumentException if {@code predominantDirection == Direction.BOTH}
   */
  public List<Long> resolve(CounterpartySelector where) {
    if (where != null && where.predominantDirection() == Direction.BOTH) {
      throw new IllegalArgumentException("predominantDirection must be DBIT or CRDT, not BOTH");
    }

    List<Condition> conditions = new ArrayList<>();
    if (where != null) {
      if (Boolean.TRUE.equals(where.untagged())) {
        conditions.add(
            DSL.notExists(
                DSL.selectOne()
                    .from(COUNTERPARTY_TAGS)
                    .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))));
      }
      if (where.namePattern() != null && !where.namePattern().isBlank()) {
        conditions.add(COUNTERPARTIES.DISPLAY_NAME.likeIgnoreCase("%" + where.namePattern() + "%"));
      }
      if (where.predominantDirection() != null) {
        conditions.add(V_COUNTERPARTY_EVIDENCE.DIRECTION.eq(where.predominantDirection().name()));
      }
    }

    var rows =
        db.select(
                COUNTERPARTIES.ID,
                RECURRING.ID,
                RECURRING.CADENCE,
                RECURRING.TYPICAL_AMOUNT,
                RECURRING.AMOUNT_MIN,
                RECURRING.AMOUNT_MAX,
                RECURRING.FIRST_SEEN,
                RECURRING.LAST_SEEN,
                RECURRING.OCCURRENCE_COUNT,
                RECURRING.SOURCE,
                RECURRING.CONFIDENCE,
                V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID,
                V_COUNTERPARTY_EVIDENCE.TXN_COUNT,
                V_COUNTERPARTY_EVIDENCE.FIRST_SEEN,
                V_COUNTERPARTY_EVIDENCE.LAST_SEEN,
                V_COUNTERPARTY_EVIDENCE.SPAN_DAYS,
                V_COUNTERPARTY_EVIDENCE.TOTAL_AMOUNT,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_MIN,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_MAX,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_AVG,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_STDDEV,
                V_COUNTERPARTY_EVIDENCE.MEDIAN_GAP_DAYS,
                V_COUNTERPARTY_EVIDENCE.SPEND_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.DIRECTION,
                V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL)
            .from(COUNTERPARTIES)
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .leftJoin(RECURRING)
            .on(RECURRING.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .where(conditions.isEmpty() ? DSL.trueCondition() : DSL.and(conditions))
            .fetch();

    List<Long> ids = new ArrayList<>();
    for (Record row : rows) {
      long id = row.get(COUNTERPARTIES.ID);
      if (where != null && where.minAnnualCost() != null) {
        CounterpartyEvidence evidence = ReadTools.mapEvidence(row, id);
        RecurringView recurring = ReadTools.mapRecurring(row);
        if (AnnualCost.estimate(recurring, evidence).compareTo(where.minAnnualCost()) < 0) {
          continue;
        }
      }
      ids.add(id);
    }
    return ids;
  }
}
