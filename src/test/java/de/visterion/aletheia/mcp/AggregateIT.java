package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
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

class AggregateIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ReadTools readTools;

  private static final String RAW = "{}";
  private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
  private static final LocalDate TO = LocalDate.of(2025, 12, 31);

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
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
      String iban,
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
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, iban)
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  @Test
  void totalSumDbitOverRange() {
    long imp = importId();
    insertTxn(imp, "hash-d1", LocalDate.of(2025, 1, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-d2", LocalDate.of(2025, 2, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-d3", LocalDate.of(2025, 3, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-c1", LocalDate.of(2025, 4, 5), "100.00", "CRDT", "CDTR-A", null, "Alpha Co");
    resolver.run(null);

    var out =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.DBIT, false, null,
            null);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).period()).isEqualTo("total");
    assertThat(out.get(0).value()).isEqualByComparingTo("30.00");
  }

  @Test
  void bothNetsSignedAmount() {
    long imp = importId();
    insertTxn(imp, "hash-d1", LocalDate.of(2025, 1, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-d2", LocalDate.of(2025, 2, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-d3", LocalDate.of(2025, 3, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-c1", LocalDate.of(2025, 4, 5), "100.00", "CRDT", "CDTR-A", null, "Alpha Co");
    resolver.run(null);

    var out =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.BOTH, false, null,
            null);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).value()).isEqualByComparingTo("70.00"); // 100 credit - 30 debit
  }

  @Test
  void byCounterpartyKeysOnIdNotName() {
    long imp = importId();
    // Same display_name "Same Co", but distinct identity: creditor_id vs iban.
    insertTxn(imp, "hash-same-1", LocalDate.of(2025, 3, 1), "10.00", "DBIT", "CDTR-SAME", null, "Same Co");
    insertTxn(imp, "hash-same-2", LocalDate.of(2025, 5, 1), "20.00", "DBIT", null, "DE00SAMECO000000000000", "Same Co");
    resolver.run(null);

    var out =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.YEAR, AggregateMetric.SUM, Direction.DBIT, true, null,
            null);

    assertThat(out).hasSize(2);
    assertThat(out).extracting(AggregateBucket::counterpartyId).doesNotHaveDuplicates();
    assertThat(out.stream().filter(b -> "Same Co".equals(b.displayName())).count()).isEqualTo(2);
  }

  @Test
  void unscopedTotalIncludesUnresolvedTransactions() {
    long imp = importId();
    // Unresolved cash withdrawal: no creditor_id/iban/name.
    insertTxn(imp, "hash-cash", LocalDate.of(2025, 6, 1), "15.00", "DBIT", null, null, null);
    insertTxn(imp, "hash-resolved", LocalDate.of(2025, 6, 2), "5.00", "DBIT", "CDTR-X", null, "X Co");
    resolver.run(null);

    var out =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.DBIT, false, null,
            null);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).value()).isEqualByComparingTo("20.00");
  }

  @Test
  void medianOfEvenCountInterpolatesMidpoint() {
    long imp = importId();
    insertTxn(imp, "hash-m1", LocalDate.of(2025, 1, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-m2", LocalDate.of(2025, 2, 5), "20.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-m3", LocalDate.of(2025, 3, 5), "30.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-m4", LocalDate.of(2025, 4, 5), "100.00", "DBIT", "CDTR-A", null, "Alpha Co");
    resolver.run(null);

    var out =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.MEDIAN, Direction.DBIT, false, null,
            null);

    assertThat(out).hasSize(1);
    // percentile_cont(0.5) over {10,20,30,100} interpolates the even-count midpoint: (20+30)/2.
    assertThat(out.get(0).value()).isEqualByComparingTo("25.00");
  }

  @Test
  void medianOfOddCountReturnsMiddleValue() {
    long imp = importId();
    insertTxn(imp, "hash-o1", LocalDate.of(2025, 1, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-o2", LocalDate.of(2025, 2, 5), "20.00", "DBIT", "CDTR-A", null, "Alpha Co");
    insertTxn(imp, "hash-o3", LocalDate.of(2025, 3, 5), "100.00", "DBIT", "CDTR-A", null, "Alpha Co");
    resolver.run(null);

    var out =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.MEDIAN, Direction.DBIT, false, null,
            null);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).value()).isEqualByComparingTo("20.00");
  }

  @Test
  void emptyRangeSumAndCountReturnZeroNotNull() {
    long imp = importId();
    insertTxn(imp, "hash-outside", LocalDate.of(2024, 1, 5), "10.00", "DBIT", "CDTR-A", null, "Alpha Co");
    resolver.run(null);

    var sumOut =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.DBIT, false, null,
            null);

    assertThat(sumOut).hasSize(1);
    assertThat(sumOut.get(0).value()).isEqualByComparingTo("0");

    var countOut =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.COUNT, Direction.DBIT, false, null,
            null);

    assertThat(countOut).hasSize(1);
    assertThat(countOut.get(0).value()).isEqualByComparingTo("0");
  }

  @Test
  void aggregateAppliesLogicalFilterHidingSplitParentsShowingOnlyChildren() {
    // TDD driver for core NOT EXISTS filter in aggregate (both unscoped and scoped paths).
    // Parent row is hidden once children with split_parent_* exist; children are shown.
    long imp = importId();
    String parentHash = "parent-for-split-agg-001";
    insertTxn(
        imp, parentHash, LocalDate.of(2025, 7, 1), "100.00", "DBIT", "CDTR-SPLIT", null, "Split Merchant");

    // Child 1: purchase part (keeps attribution)
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-purchase-synth")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2025, 7, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("60.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Split Merchant")
        .set(TRANSACTIONS.CREDITOR_ID, "CDTR-SPLIT")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parentHash)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();

    // Child 2: pseudo part (e.g. Bargeld)
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-bargeld-synth")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2025, 7, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("40.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Bargeld")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parentHash)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();

    // Unscoped path (no joinIdentity): should aggregate only children (100 total), hide parent.
    var unscoped =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.DBIT, false, null, null);
    assertThat(unscoped).hasSize(1);
    assertThat(unscoped.get(0).value()).isEqualByComparingTo("100.00");

    // Create CP for the merchant identity (needed for scoped join path).
    long cpId =
        db.insertInto(COUNTERPARTIES)
            .set(COUNTERPARTIES.IDENTITY_TYPE, "creditor_id")
            .set(COUNTERPARTIES.IDENTITY_VALUE, "CDTR-SPLIT")
            .set(COUNTERPARTIES.DISPLAY_NAME, "Split Merchant")
            .set(COUNTERPARTIES.STATUS, "open")
            .returning(COUNTERPARTIES.ID)
            .fetchOne()
            .get(COUNTERPARTIES.ID);

    // Scoped path (via counterpartyIds -> joinIdentity + IDENTITY_RESOLVED path): only the 60 child.
    var scopedViaId =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.DBIT, false, List.of(cpId), null);
    assertThat(scopedViaId).hasSize(1);
    assertThat(scopedViaId.get(0).value()).isEqualByComparingTo("60.00");

    // Regression: an unsplit transaction (no children, split_parent_* remain NULL) is still
    // visible with no behavior change.
    insertTxn(imp, "unsplit-visible-agg", LocalDate.of(2025, 7, 2), "7.00", "DBIT", "CDTR-UNSP", null, "Unsplit Co");
    resolver.run(null);
    long unsplitCp =
        db.select(COUNTERPARTIES.ID)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-UNSP"))
            .fetchOne(COUNTERPARTIES.ID);
    var unsplitAgg =
        readTools.aggregate(
            FROM, TO, AggregateGroupBy.TOTAL, AggregateMetric.SUM, Direction.DBIT, false, List.of(unsplitCp), null);
    assertThat(unsplitAgg).hasSize(1);
    assertThat(unsplitAgg.get(0).value()).isEqualByComparingTo("7.00");
  }
}
