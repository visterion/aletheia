package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.V_COUNTERPARTY_EVIDENCE;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    if (where != null) {
      if (where.domainIn() != null && where.domainIn().isEmpty()) {
        throw new IllegalArgumentException(
            "empty domainIn is ambiguous; omit the field for no filter");
      }
      if (where.natureIn() != null && where.natureIn().isEmpty()) {
        throw new IllegalArgumentException(
            "empty natureIn is ambiguous; omit the field for no filter");
      }
      if (where.natureNotIn() != null && where.natureNotIn().isEmpty()) {
        throw new IllegalArgumentException(
            "empty natureNotIn is ambiguous; omit the field for no filter");
      }
      if (where.domainNotIn() != null && where.domainNotIn().isEmpty()) {
        throw new IllegalArgumentException(
            "empty domainNotIn is ambiguous; omit the field for no filter");
      }
      if (where.txnCountMax() != null && where.txnCountMax() < 0) {
        throw new IllegalArgumentException("txnCountMax must not be negative");
      }
      if (where.amountMin() != null && where.amountMin().signum() < 0) {
        throw new IllegalArgumentException("amountMin must not be negative");
      }
      if (where.amountMax() != null && where.amountMax().signum() < 0) {
        throw new IllegalArgumentException("amountMax must not be negative");
      }
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
      if (where.domainIn() != null) {
        conditions.add(tagExists("domain", where.domainIn()));
      }
      if (where.natureIn() != null) {
        conditions.add(tagExists("nature", where.natureIn()));
      }
      if (where.reviewed() != null) {
        conditions.add(COUNTERPARTIES.REVIEWED.eq(where.reviewed()));
      }
      if (where.hasContract() != null) {
        Condition exists = contractExists();
        conditions.add(where.hasContract() ? exists : DSL.not(exists));
      }
      if (where.txnCountMax() != null) {
        conditions.add(
            DSL.coalesce(V_COUNTERPARTY_EVIDENCE.TXN_COUNT, DSL.inline(0L)).le(where.txnCountMax()));
      }
      if (where.natureNotIn() != null) {
        conditions.add(DSL.not(tagExists("nature", where.natureNotIn())));
      }
      if (where.domainNotIn() != null) {
        conditions.add(DSL.not(tagExists("domain", where.domainNotIn())));
      }
      if (where.amountMin() != null) {
        conditions.add(V_COUNTERPARTY_EVIDENCE.AMOUNT_MAX.ge(where.amountMin()));
      }
      if (where.amountMax() != null) {
        conditions.add(V_COUNTERPARTY_EVIDENCE.AMOUNT_MAX.le(where.amountMax()));
      }
      if (where.lastSeenBefore() != null) {
        conditions.add(V_COUNTERPARTY_EVIDENCE.LAST_SEEN.le(where.lastSeenBefore()));
      }
      if (where.lastSeenAfter() != null) {
        conditions.add(V_COUNTERPARTY_EVIDENCE.LAST_SEEN.ge(where.lastSeenAfter()));
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

    // A counterparty may now have multiple `recurring` rows (one per contract, TP1) -- the
    // leftJoin(RECURRING) above fans a split counterparty into multiple rows here. Dedupe by id
    // so every counterparty is resolved exactly once: a counterparty qualifies as soon as any one
    // of its fanned-out rows passes the minAnnualCost check (acceptable for the selector --
    // precise per-contract filtering is out of scope, TP1 plan "Deferred").
    Set<Long> seen = new LinkedHashSet<>();
    for (Record row : rows) {
      long id = row.get(COUNTERPARTIES.ID);
      if (!seen.contains(id)) {
        if (where != null && where.minAnnualCost() != null) {
          CounterpartyEvidence evidence = ReadTools.mapEvidence(row, id);
          RecurringView recurring = ReadTools.mapRecurring(row);
          if (AnnualCost.estimate(recurring, evidence).compareTo(where.minAnnualCost()) < 0) {
            continue;
          }
        }
        seen.add(id);
      }
    }
    return new ArrayList<>(seen);
  }

  private static Condition contractExists() {
    return DSL.exists(
        DSL.selectOne()
            .from(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID)));
  }

  private static Condition tagExists(String dimension, List<String> values) {
    return DSL.exists(
        DSL.selectOne()
            .from(COUNTERPARTY_TAGS)
            .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .and(COUNTERPARTY_TAGS.DIMENSION.eq(dimension))
            .and(COUNTERPARTY_TAGS.VALUE.in(values)));
  }
}
