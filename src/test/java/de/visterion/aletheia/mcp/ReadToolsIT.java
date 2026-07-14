package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class ReadToolsIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ReadTools readTools;

  private static final String RAW = "{}";

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

  private long counterpartyIdFor(String identityValue) {
    return db.select(COUNTERPARTIES.ID)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue))
        .fetchOne(COUNTERPARTIES.ID);
  }

  @Test
  void listCounterpartiesOrdersBySpendLast365dDescendingByDefault() {
    long imp = importId();
    // Big spender: 2 debits totalling 500 in the last year.
    insertTxn(imp, "hash-big-1", LocalDate.now().minusDays(10), "250.00", "DBIT", "CDTR-BIG", null, "Big Spender");
    insertTxn(imp, "hash-big-2", LocalDate.now().minusDays(40), "250.00", "DBIT", "CDTR-BIG", null, "Big Spender");
    // Small spender: 1 debit of 5.
    insertTxn(imp, "hash-small-1", LocalDate.now().minusDays(5), "5.00", "DBIT", "CDTR-SMALL", null, "Small Spender");

    resolver.run(null);

    List<CounterpartySummary> summaries = readTools.listCounterparties(null, null);

    assertThat(summaries).hasSize(2);
    assertThat(summaries.get(0).displayName()).isEqualTo("Big Spender");
    assertThat(summaries.get(0).evidence()).isNotNull();
    assertThat(summaries.get(0).evidence().spendLast365d()).isEqualByComparingTo("500.00");
    assertThat(summaries.get(1).displayName()).isEqualTo("Small Spender");
  }

  @Test
  void listCounterpartiesFiltersByHasRecurring() {
    long imp = importId();
    insertTxn(imp, "hash-r1", LocalDate.now().minusMonths(1), "10.00", "DBIT", "CDTR-REC", null, "Recurring Co");
    insertTxn(imp, "hash-nr1", LocalDate.now().minusDays(3), "20.00", "DBIT", "CDTR-NOREC", null, "Non Recurring Co");
    resolver.run(null);

    long recurringCounterpartyId = counterpartyIdFor("CDTR-REC");
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, recurringCounterpartyId)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("10.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();

    List<CounterpartySummary> filtered =
        readTools.listCounterparties(CounterpartyFilter.has_recurring, null);

    assertThat(filtered).hasSize(1);
    assertThat(filtered.get(0).displayName()).isEqualTo("Recurring Co");
    assertThat(filtered.get(0).recurring()).isNotNull();
    assertThat(filtered.get(0).recurring().cadence()).isEqualTo("monthly");
  }

  @Test
  void getReviewQueueReturnsOnlyOpenCounterpartiesPrioritizedByAnnualCost() {
    long imp = importId();
    insertTxn(imp, "hash-open-1", LocalDate.now().minusDays(5), "50.00", "DBIT", "CDTR-OPEN", null, "Open Co");
    insertTxn(imp, "hash-conf-1", LocalDate.now().minusDays(5), "999.00", "DBIT", "CDTR-CONF", null, "Confirmed Co");
    resolver.run(null);

    long confirmedId = counterpartyIdFor("CDTR-CONF");
    db.update(COUNTERPARTIES).set(COUNTERPARTIES.STATUS, "confirmed").where(COUNTERPARTIES.ID.eq(confirmedId)).execute();

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, true);

    assertThat(queue).hasSize(1);
    assertThat(queue.get(0).displayName()).isEqualTo("Open Co");
  }

  @Test
  void getReviewQueueOrdersByRecurringAnnualCostOverSpendLast365d() {
    long imp = importId();
    // No recurring: spend_last_365d = 40.
    insertTxn(imp, "hash-a1", LocalDate.now().minusDays(5), "40.00", "DBIT", "CDTR-A", null, "Plain Spender");
    // Recurring monthly at 10 -> annual estimate 120, higher priority despite lower raw spend.
    insertTxn(imp, "hash-b1", LocalDate.now().minusDays(5), "10.00", "DBIT", "CDTR-B", null, "Recurring Small");
    resolver.run(null);

    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyIdFor("CDTR-B"))
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("10.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, true);

    assertThat(queue).hasSize(2);
    assertThat(queue.get(0).displayName()).isEqualTo("Recurring Small");
    assertThat(queue.get(0).annualCostEstimate()).isEqualByComparingTo("120.00");
    assertThat(queue.get(1).displayName()).isEqualTo("Plain Spender");
  }

  @Test
  void getReviewQueueExcludesCrdtPredominantCounterparties() {
    long imp = importId();
    // Salary: predominant CRDT, open status -- must not clutter the obligations queue.
    insertTxn(imp, "hash-sal-1", LocalDate.now().minusDays(5), "3000.00", "CRDT", "CDTR-SALARY", null, "Salary");
    insertTxn(imp, "hash-sal-2", LocalDate.now().minusDays(35), "3000.00", "CRDT", "CDTR-SALARY", null, "Salary");
    // A normal DBIT-predominant open counterparty, must still appear.
    insertTxn(imp, "hash-obl-1", LocalDate.now().minusDays(5), "20.00", "DBIT", "CDTR-OBL", null, "Obligation Co");
    resolver.run(null);

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, true);

    assertThat(queue).extracting(ReviewQueueEntry::displayName).containsExactly("Obligation Co");
  }

  @Test
  void getReviewQueueKeepsOpenCounterpartyWithNoEvidenceRow() {
    // No transactions at all -- the v_counterparty_evidence LEFT JOIN yields NULL/direction NULL.
    // Must still appear: "nothing skips human review".
    db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "creditor_id")
        .set(COUNTERPARTIES.IDENTITY_VALUE, "CDTR-NOEVIDENCE")
        .set(COUNTERPARTIES.DISPLAY_NAME, "No Evidence Co")
        .set(COUNTERPARTIES.STATUS, "open")
        .execute();

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, true);

    assertThat(queue).extracting(ReviewQueueEntry::displayName).containsExactly("No Evidence Co");
  }

  @Test
  void getReviewQueueCompactDefaultOmitsEvidenceAndRecurringBlobs() {
    long imp = importId();
    insertTxn(imp, "hash-compact-1", LocalDate.now().minusDays(5), "10.00", "DBIT", "CDTR-COMPACT", null, "Compact Co");
    resolver.run(null);
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyIdFor("CDTR-COMPACT"))
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("10.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();

    List<ReviewQueueEntry> compact = readTools.getReviewQueue(null, false);

    assertThat(compact).hasSize(1);
    ReviewQueueEntry entry = compact.get(0);
    assertThat(entry.evidence()).isNull();
    assertThat(entry.recurring()).isNull();
    assertThat(entry.cadence()).isEqualTo("monthly");
    assertThat(entry.annualCostEstimate()).isEqualByComparingTo("120.00");
    assertThat(entry.txnCount()).isEqualTo(1);
    assertThat(entry.lastSeen()).isEqualTo(LocalDate.now().minusDays(5));

    List<ReviewQueueEntry> verbose = readTools.getReviewQueue(null, true);
    assertThat(verbose.get(0).evidence()).isNotNull();
    assertThat(verbose.get(0).recurring()).isNotNull();
  }

  @Test
  void listUnmatchedRecurringReturnsOnlyRecurringWithoutAContract() {
    long imp = importId();
    insertTxn(imp, "hash-m1", LocalDate.now().minusDays(5), "15.00", "DBIT", "CDTR-MATCHED", null, "Matched Co");
    insertTxn(imp, "hash-u1", LocalDate.now().minusDays(5), "25.00", "DBIT", "CDTR-UNMATCHED", null, "Unmatched Co");
    resolver.run(null);

    long matchedId = counterpartyIdFor("CDTR-MATCHED");
    long unmatchedId = counterpartyIdFor("CDTR-UNMATCHED");

    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, matchedId)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("15.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, unmatchedId)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("25.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();
    db.insertInto(CONTRACTS).set(CONTRACTS.COUNTERPARTY_ID, matchedId).set(CONTRACTS.STATUS, "linked").execute();

    List<UnmatchedRecurringEntry> unmatched = readTools.listUnmatchedRecurring();

    assertThat(unmatched).hasSize(1);
    assertThat(unmatched.get(0).displayName()).isEqualTo("Unmatched Co");
  }

  @Test
  void counterpartyTransactionsReturnsTheUnderlyingBookings() {
    long imp = importId();
    insertTxn(imp, "hash-t1", LocalDate.of(2026, 1, 1), "10.00", "DBIT", "CDTR-T", null, "Txn Co");
    insertTxn(imp, "hash-t2", LocalDate.of(2026, 2, 1), "10.00", "DBIT", "CDTR-T", null, "Txn Co");
    insertTxn(imp, "hash-other", LocalDate.of(2026, 2, 1), "99.00", "DBIT", "CDTR-OTHER", null, "Other Co");
    resolver.run(null);

    long id = counterpartyIdFor("CDTR-T");

    List<TransactionView> txns = readTools.counterpartyTransactions(id, null, null, null);

    assertThat(txns).hasSize(2);
    assertThat(txns).extracting(TransactionView::amount).allMatch(a -> a.compareTo(new BigDecimal("10.00")) == 0);
    assertThat(txns.get(0).bookingDate()).isEqualTo(LocalDate.of(2026, 2, 1)); // DESC order
  }

  @Test
  void counterpartyTransactionsWithAbsoluteRangeReturnsOnlyBookingsInThatRange() {
    long imp = importId();
    insertTxn(imp, "hash-r2024", LocalDate.of(2024, 6, 1), "10.00", "DBIT", "CDTR-RANGE", null, "Range Co");
    insertTxn(imp, "hash-r2025-1", LocalDate.of(2025, 1, 15), "10.00", "DBIT", "CDTR-RANGE", null, "Range Co");
    insertTxn(imp, "hash-r2025-2", LocalDate.of(2025, 12, 20), "10.00", "DBIT", "CDTR-RANGE", null, "Range Co");
    resolver.run(null);

    long id = counterpartyIdFor("CDTR-RANGE");

    List<TransactionView> txns =
        readTools.counterpartyTransactions(id, null, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

    assertThat(txns).hasSize(2);
    assertThat(txns)
        .extracting(TransactionView::bookingDate)
        .containsExactlyInAnyOrder(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 12, 20));
  }

  @Test
  void counterpartyTransactionsAbsoluteRangeWinsOverPeriod() {
    long imp = importId();
    insertTxn(imp, "hash-w2024", LocalDate.of(2024, 6, 1), "10.00", "DBIT", "CDTR-WINS", null, "Wins Co");
    insertTxn(imp, "hash-w2025", LocalDate.of(2025, 6, 1), "10.00", "DBIT", "CDTR-WINS", null, "Wins Co");
    resolver.run(null);

    long id = counterpartyIdFor("CDTR-WINS");

    // period=3650 (~10 years) would normally include both, but the absolute range should win
    // and restrict to 2025 only.
    List<TransactionView> txns =
        readTools.counterpartyTransactions(id, 3650, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

    assertThat(txns).hasSize(1);
    assertThat(txns.get(0).bookingDate()).isEqualTo(LocalDate.of(2025, 6, 1));
  }

  @Test
  void sqlQueryRunsASelectAndReturnsRows() {
    long imp = importId();
    insertTxn(imp, "hash-sq1", LocalDate.now(), "1.00", "DBIT", "CDTR-SQ", null, "Sql Co");

    SqlQueryResult result = readTools.sqlQuery("SELECT count(*) AS n FROM transactions");

    assertThat(result.columns()).containsExactly("n");
    assertThat(result.rows()).hasSize(1);
    assertThat(((Number) result.rows().get(0).get("n")).longValue()).isEqualTo(1L);
  }

  @Test
  void sqlQueryRejectsNonSelectStatements() {
    long imp = importId();
    insertTxn(imp, "hash-guard1", LocalDate.now(), "1.00", "DBIT", "CDTR-GUARD", null, "Guard Co");

    assertThatThrownBy(() -> readTools.sqlQuery("DELETE FROM transactions"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(db.fetchCount(TRANSACTIONS)).isEqualTo(1);
  }

  @Test
  void sqlQueryRejectsStackedStatements() {
    long imp = importId();
    insertTxn(imp, "hash-guard2", LocalDate.now(), "1.00", "DBIT", "CDTR-GUARD2", null, "Guard Co 2");

    assertThatThrownBy(() -> readTools.sqlQuery("SELECT 1; DROP TABLE transactions"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(db.fetchCount(TRANSACTIONS)).isEqualTo(1);
  }

  @Test
  void sqlQueryRejectsSelectIntoTableCreation() {
    long imp = importId();
    insertTxn(imp, "hash-guard3", LocalDate.now(), "1.00", "DBIT", "CDTR-GUARD3", null, "Guard Co 3");

    assertThatThrownBy(() -> readTools.sqlQuery("SELECT * INTO evil FROM transactions"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(db.fetchCount(TRANSACTIONS)).isEqualTo(1);
    Integer evilTableCount =
        db.fetchOne(
                "SELECT count(*) AS n FROM information_schema.tables WHERE table_name = 'evil'")
            .get("n", Integer.class);
    assertThat(evilTableCount).isZero();
  }

  @Test
  void sqlQueryAllowsIntoAsAnAliasSuffixNotARealSelectInto() {
    long imp = importId();
    insertTxn(imp, "hash-guard4", LocalDate.now(), "1.00", "DBIT", "CDTR-GUARD4", null, "Guard Co 4");

    SqlQueryResult result =
        readTools.sqlQuery("SELECT amount AS into_total FROM transactions LIMIT 1");

    assertThat(result.columns()).containsExactly("into_total");
    assertThat(result.rows()).hasSize(1);
  }

  @Test
  void sqlQueryAllowsIntoInsideAStringLiteral() {
    long imp = importId();
    insertTxn(imp, "hash-guard5", LocalDate.now(), "1.00", "DBIT", "CDTR-GUARD5", null, "Guard Co 5");

    SqlQueryResult result = readTools.sqlQuery("SELECT 'paid into account' AS note");

    assertThat(result.columns()).containsExactly("note");
    assertThat(result.rows().get(0).get("note")).isEqualTo("paid into account");
  }
}
