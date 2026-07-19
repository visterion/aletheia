package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
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
 * Task 3 (sub-project A/P1 counterparty merge): every MCP read tool must pool a folded source's
 * bookings onto its canonical target ({@code counterparty_alias}) and must never enumerate a
 * counterparty once {@code merged_into} is set on it.
 */
class AliasReadLayerIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ReadTools readTools;
  @Autowired OperatingGuideService operatingGuideService;

  private static final String RAW = "{}";

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

  private void insertAlias(String identityType, String identityValue, long canonicalCounterpartyId) {
    db.execute(
        "INSERT INTO counterparty_alias (identity_type, identity_value, canonical_counterparty_id) "
            + "VALUES (?, ?, ?)",
        identityType,
        identityValue,
        canonicalCounterpartyId);
  }

  /**
   * Seeds counterparty A (target) and B (source, later folded into A): both DBIT-predominant with
   * a monthly two-booking series, then aliases B's identity to A and marks B {@code merged_into}
   * A. Also seeds one review-queue-relevant row per read path so every exclusion below is
   * meaningful (not vacuously true because B had nothing to list in the first place).
   *
   * @return {@code [a, b]} counterparty ids
   */
  private long[] seedMergedPair() {
    long imp = importId();
    insertTxn(imp, "hash-a1", LocalDate.now().minusDays(10), "50.00", "DBIT", "CDTR-A", "Merchant A");
    insertTxn(imp, "hash-a2", LocalDate.now().minusDays(40), "50.00", "DBIT", "CDTR-A", "Merchant A");
    insertTxn(imp, "hash-b1", LocalDate.now().minusDays(10), "30.00", "DBIT", "CDTR-B", "Merchant B");
    insertTxn(imp, "hash-b2", LocalDate.now().minusDays(40), "30.00", "DBIT", "CDTR-B", "Merchant B");

    resolver.run(null);

    long a = counterpartyIdFor("CDTR-A");
    long b = counterpartyIdFor("CDTR-B");

    insertAlias("creditor_id", "CDTR-B", a);
    db.update(COUNTERPARTIES).set(COUNTERPARTIES.MERGED_INTO, a).where(COUNTERPARTIES.ID.eq(b)).execute();

    // A tag held only by B -- taxonomy must drop it entirely once B is excluded.
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, b)
        .set(COUNTERPARTY_TAGS.DIMENSION, "domain")
        .set(COUNTERPARTY_TAGS.VALUE, "evil-merge-tag")
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .execute();
    // nature=zahlungsdienst on B -- must not count toward the opaque-passthrough wake_up total.
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, b)
        .set(COUNTERPARTY_TAGS.DIMENSION, "nature")
        .set(COUNTERPARTY_TAGS.VALUE, "zahlungsdienst")
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .execute();

    // Open contract on B -- must not surface in get_review_queue's contract-grain path.
    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, b)
        .set(CONTRACTS.STATUS, "open")
        .execute();
    // Confirmed contract on B -- must not surface in obligations_register. Distinct mandate_id:
    // (counterparty_id, mandate_id) is unique, and B already has an open contract with a NULL
    // mandate_id above.
    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, b)
        .set(CONTRACTS.MANDATE_ID, "MND-B-CONFIRMED")
        .set(CONTRACTS.STATUS, "confirmed")
        .execute();
    // Mandate-less recurring series on B -- must not surface in list_unmatched_recurring.
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, b)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("30.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();

    return new long[] {a, b};
  }

  @Test
  void counterpartyTransactionsPoolsFoldedSourceOntoTargetAndReturnsNoneForFoldedSourceItself() {
    long[] ids = seedMergedPair();
    long a = ids[0];
    long b = ids[1];

    List<TransactionView> forTarget = readTools.counterpartyTransactions(a, null, null, null);
    assertThat(forTarget).hasSize(4);

    List<TransactionView> forFoldedSource = readTools.counterpartyTransactions(b, null, null, null);
    assertThat(forFoldedSource).isEmpty();
  }

  @Test
  void listCounterpartiesReturnsTargetWithAliasEntryAndNeverListsTheFoldedSource() {
    long[] ids = seedMergedPair();
    long a = ids[0];
    long b = ids[1];

    List<CounterpartySummary> summaries = readTools.listCounterparties(null, null);

    assertThat(summaries).extracting(CounterpartySummary::id).doesNotContain(b);

    CounterpartySummary target =
        summaries.stream().filter(s -> s.id() == a).findFirst().orElseThrow();
    assertThat(target.aliases())
        .contains(new CounterpartyAliasView("creditor_id", "CDTR-B"));
  }

  @Test
  void getReviewQueueNeverListsTheFoldedSource() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, true);

    assertThat(queue).extracting(ReviewQueueEntry::id).doesNotContain(b);
    assertThat(queue).extracting(ReviewQueueEntry::displayName).doesNotContain("Merchant B");
  }

  @Test
  void obligationsRegisterNeverListsTheFoldedSource() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).extracting(ObligationRow::counterpartyId).doesNotContain(b);
  }

  @Test
  void listUnmatchedRecurringNeverListsTheFoldedSource() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    List<UnmatchedRecurringEntry> unmatched = readTools.listUnmatchedRecurring(null, null);

    assertThat(unmatched).extracting(UnmatchedRecurringEntry::counterpartyId).doesNotContain(b);
  }

  @Test
  void listIncomeNeverListsTheFoldedSource() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    List<IncomeRow> income = readTools.listIncome();

    assertThat(income).extracting(IncomeRow::counterpartyId).doesNotContain(b);
  }

  @Test
  void taxonomyDropsATagValueHeldOnlyByTheFoldedSource() {
    seedMergedPair();

    List<TaxonomyDimension> dimensions = readTools.taxonomy();

    boolean hasEvilTag =
        dimensions.stream()
            .filter(d -> "domain".equals(d.dimension()))
            .flatMap(d -> d.values().stream())
            .anyMatch(v -> "evil-merge-tag".equals(v.value()));
    assertThat(hasEvilTag).isFalse();
  }

  @Test
  void wakeUpReviewCountsExcludeTheFoldedSource() {
    seedMergedPair();

    String wakeUp = operatingGuideService.wakeUp();

    // Only A (unreviewed, open) counts -- B is unreviewed too but merged_into-excluded.
    assertThat(wakeUp).contains("Unreviewed counterparties: 1");
    // B is the only counterparty carrying nature=zahlungsdienst -- merged_into excludes it.
    assertThat(wakeUp).contains("Payment-service passthroughs still opaque: 0");
  }

  @Test
  void describeSchemaListsCounterpartyAliasTable() {
    List<SchemaColumn> columns = readTools.describeSchema();

    assertThat(columns).extracting(SchemaColumn::table).contains("counterparty_alias");
  }
}
