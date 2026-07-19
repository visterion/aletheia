package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.ContractResolver;
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
 * Regression coverage for the {@code recurring} 1:N fan-out (TP1 Task 6): since V9 a counterparty
 * can have multiple {@code contracts} rows (one per {@code mandate_id}), each with its own {@code
 * recurring} row -- any read path that {@code leftJoin(RECURRING)}s straight off {@code
 * counterparties.id} without deduping fans a single counterparty into multiple result rows.
 */
class RecurringFanOutRegressionIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;
  @Autowired ReadTools readTools;
  @Autowired WriteTools writeTools;
  @Autowired CounterpartySelectorResolver selectorResolver;

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

  private void insertMandateTxn(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String creditorId,
      String mandateId) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Split Co")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private long counterpartyIdFor(String identityValue) {
    return db.select(COUNTERPARTIES.ID)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue))
        .fetchOne(COUNTERPARTIES.ID);
  }

  /** Seeds one counterparty with 2 contracts (2 mandate_ids, each booked in >= 2 months). */
  private long seedSplitCounterparty() {
    long imp = importId();
    insertMandateTxn(imp, "hash-m1-a", LocalDate.now().minusMonths(1), "10.00", "CDTR-SPLIT", "M1");
    insertMandateTxn(imp, "hash-m1-b", LocalDate.now().minusMonths(2), "10.00", "CDTR-SPLIT", "M1");
    insertMandateTxn(imp, "hash-m2-a", LocalDate.now().minusMonths(1), "20.00", "CDTR-SPLIT", "M2");
    insertMandateTxn(imp, "hash-m2-b", LocalDate.now().minusMonths(2), "20.00", "CDTR-SPLIT", "M2");

    counterpartyResolver.run(null);
    contractResolver.run(null);

    return counterpartyIdFor("CDTR-SPLIT");
  }

  @Test
  void listCounterpartiesReturnsTheSplitCounterpartyExactlyOnce() {
    seedSplitCounterparty();

    List<CounterpartySummary> summaries = readTools.listCounterparties(null, null);

    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).displayName()).isEqualTo("Split Co");
    assertThat(summaries.get(0).contractCount()).isEqualTo(2);
  }

  @Test
  void selectorResolverReturnsTheSplitCounterpartyIdExactlyOnce() {
    long splitId = seedSplitCounterparty();

    CounterpartySelector where = new CounterpartySelector(null, "Split", null, null, null, null, null, null, null, null, null, null, null, null, null);
    List<Long> ids = selectorResolver.resolve(where);

    assertThat(ids).containsExactly(splitId);
  }

  @Test
  void classifyCounterpartyViaWhereSelectorWritesExactlyOneHistoryRowPerDimension() {
    seedSplitCounterparty();

    CounterpartySelector where = new CounterpartySelector(null, "Split", null, null, null, null, null, null, null, null, null, null, null, null, null);
    writeTools.classifyCounterparty(
        null, where, List.of(new TagInput("domain", "utilities")), TagSource.auto, null, null);

    int historyRows =
        db.fetchCount(
            db.selectFrom(COUNTERPARTY_HISTORY)
                .where(COUNTERPARTY_HISTORY.FIELD.eq("tag:domain")));

    assertThat(historyRows).isEqualTo(1);
  }

  @Test
  void listUnmatchedRecurringIncludesUnlinkedMandateContractAndMandatelessRecurring() {
    long splitId = seedSplitCounterparty();

    // M2's contract is already documented in HiveMem -- must be excluded.
    db.update(CONTRACTS)
        .set(CONTRACTS.HIVEMEM_CELL_ID, "cell-123")
        .where(CONTRACTS.COUNTERPARTY_ID.eq(splitId))
        .and(CONTRACTS.MANDATE_ID.eq("M2"))
        .execute();

    // A separate counterparty with a mandate-less auto recurring series (contract_id IS NULL).
    long imp = importId();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "hash-elv-1")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now().minusDays(5))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("30.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "ELV Co")
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, "DE00ELV")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
    counterpartyResolver.run(null);
    long elvId = counterpartyIdFor("DE00ELV");
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, elvId)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal("30.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();

    List<UnmatchedRecurringEntry> unmatched = readTools.listUnmatchedRecurring(null, null);

    assertThat(unmatched)
        .extracting(UnmatchedRecurringEntry::displayName)
        .containsExactlyInAnyOrder("Split Co", "ELV Co");
    UnmatchedRecurringEntry splitEntry =
        unmatched.stream().filter(e -> e.displayName().equals("Split Co")).findFirst().orElseThrow();
    assertThat(splitEntry.contractId()).isNotNull();
    UnmatchedRecurringEntry elvEntry =
        unmatched.stream().filter(e -> e.displayName().equals("ELV Co")).findFirst().orElseThrow();
    assertThat(elvEntry.contractId()).isNull();
  }
}
