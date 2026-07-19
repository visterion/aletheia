package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 3 (Spec B P2 display-name override): every MCP read tool that surfaces a counterparty
 * label must return {@code COALESCE(display_name_override, display_name)}, and reverting the
 * override to {@code NULL} must fall back to the derived {@code display_name} again.
 */
class DisplayNameOverrideReadIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ReadTools readTools;
  @Autowired CounterpartySelectorResolver selectorResolver;

  private static final String RAW = "{}";
  private static final String AUTO_NAME = "Auto Name";
  private static final String CUSTOM_NAME = "Custom";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "counterparty_alias, transactions, imports, counterparties RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void insertTxn(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String direction,
      String creditorId,
      String name) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, direction)
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private long counterpartyIdFor(String identityValue) {
    return db.select(COUNTERPARTIES.ID)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue))
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void setOverride(long counterpartyId, String override) {
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE, override)
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .execute();
  }

  /**
   * Seeds counterparty X (DBIT-predominant) with an open contract-less state, a confirmed
   * contract (obligations_register), and a mandate-less recurring series (list_unmatched_recurring
   * branch 2), then stamps {@code display_name_override='Custom'} directly (Task 4 adds the
   * {@code set_display_name} tool).
   *
   * @return the counterparty id
   */
  private long seedDbitCounterparty() {
    long imp = importId();
    insertTxn(imp, "hash-x1", LocalDate.now().minusDays(10), "50.00", "DBIT", "CDTR-X", AUTO_NAME);
    insertTxn(imp, "hash-x2", LocalDate.now().minusDays(40), "50.00", "DBIT", "CDTR-X", AUTO_NAME);

    resolver.run(null);

    long x = counterpartyIdFor("CDTR-X");
    setOverride(x, CUSTOM_NAME);

    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, x)
        .set(CONTRACTS.MANDATE_ID, "MND-X-CONFIRMED")
        .set(CONTRACTS.STATUS, "confirmed")
        .execute();
    // A distinct mandate_id is required: (counterparty_id, mandate_id) is unique, and X already
    // has a confirmed contract above. This open contract feeds get_review_queue's primary
    // (contract-grain) branch.
    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, x)
        .set(CONTRACTS.MANDATE_ID, "MND-X-OPEN")
        .set(CONTRACTS.STATUS, "open")
        .execute();

    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, x)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("50.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();

    return x;
  }

  /** A CRDT-predominant counterparty, needed only for {@code list_income} (direction='CRDT'). */
  private long seedCrdtCounterparty() {
    long imp = importId();
    insertTxn(imp, "hash-y1", LocalDate.now().minusDays(10), "1000.00", "CRDT", "CDTR-Y", AUTO_NAME);
    insertTxn(imp, "hash-y2", LocalDate.now().minusDays(40), "1000.00", "CRDT", "CDTR-Y", AUTO_NAME);

    resolver.run(null);

    long y = counterpartyIdFor("CDTR-Y");
    setOverride(y, CUSTOM_NAME);
    return y;
  }

  @Test
  void listCounterpartiesReturnsTheOverrideAsDisplayName() {
    long x = seedDbitCounterparty();

    List<CounterpartySummary> summaries = readTools.listCounterparties(null, null);

    CounterpartySummary summary =
        summaries.stream().filter(s -> s.id() == x).findFirst().orElseThrow();
    assertThat(summary.displayName()).isEqualTo(CUSTOM_NAME);
  }

  @Test
  void getReviewQueueReturnsTheOverrideAsDisplayName() {
    long x = seedDbitCounterparty();

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, false);

    assertThat(queue)
        .filteredOn(entry -> entry.id() == x)
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.displayName()).isEqualTo(CUSTOM_NAME));
  }

  @Test
  void obligationsRegisterReturnsTheOverrideAsDisplayName() {
    long x = seedDbitCounterparty();

    ObligationsRegister register = readTools.obligationsRegister();

    ObligationRow row =
        register.rows().stream().filter(r -> r.counterpartyId() == x).findFirst().orElseThrow();
    assertThat(row.displayName()).isEqualTo(CUSTOM_NAME);
  }

  @Test
  void listUnmatchedRecurringReturnsTheOverrideAsDisplayName() {
    long x = seedDbitCounterparty();

    List<UnmatchedRecurringEntry> unmatched = readTools.listUnmatchedRecurring(null, null);

    UnmatchedRecurringEntry entry =
        unmatched.stream().filter(e -> e.counterpartyId() == x).findFirst().orElseThrow();
    assertThat(entry.displayName()).isEqualTo(CUSTOM_NAME);
  }

  @Test
  void listIncomeReturnsTheOverrideAsDisplayName() {
    long y = seedCrdtCounterparty();

    List<IncomeRow> income = readTools.listIncome();

    IncomeRow row = income.stream().filter(r -> r.counterpartyId() == y).findFirst().orElseThrow();
    assertThat(row.displayName()).isEqualTo(CUSTOM_NAME);
  }

  @Test
  void aggregateGroupKeyReturnsTheOverrideAsDisplayName() {
    long x = seedDbitCounterparty();

    List<AggregateBucket> buckets =
        readTools.aggregate(
            LocalDate.now().minusYears(1),
            LocalDate.now(),
            AggregateGroupBy.TOTAL,
            AggregateMetric.SUM,
            Direction.BOTH,
            true,
            List.of(x),
            null);

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).displayName()).isEqualTo(CUSTOM_NAME);
  }

  @Test
  void namePatternMatchesTheOverrideNotTheDerivedDisplayName() {
    long x = seedDbitCounterparty();

    CounterpartySelector where =
        new CounterpartySelector(
            null, CUSTOM_NAME, null, null, null, null, null, null, null, null, null, null, null,
            null, null);

    List<Long> matched = selectorResolver.resolve(where);

    assertThat(matched).contains(x);
  }

  @Test
  void settingOverrideBackToNullRevertsToTheDerivedDisplayName() {
    long x = seedDbitCounterparty();
    assertThat(readTools.listCounterparties(null, null).stream()
            .filter(s -> s.id() == x)
            .findFirst()
            .orElseThrow()
            .displayName())
        .isEqualTo(CUSTOM_NAME);

    setOverride(x, null);

    CounterpartySummary summary =
        readTools.listCounterparties(null, null).stream()
            .filter(s -> s.id() == x)
            .findFirst()
            .orElseThrow();
    assertThat(summary.displayName()).isEqualTo(AUTO_NAME);
  }
}
