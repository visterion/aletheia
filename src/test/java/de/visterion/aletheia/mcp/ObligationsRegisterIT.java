package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
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

class ObligationsRegisterIT extends AbstractPostgresIT {

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

  private void confirm(long counterpartyId) {
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.STATUS, "confirmed")
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .execute();
  }

  private void insertRecurring(long counterpartyId, String cadence, String typicalAmount) {
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
        .set(RECURRING.CADENCE, cadence)
        .set(RECURRING.TYPICAL_AMOUNT, typicalAmount == null ? null : new BigDecimal(typicalAmount))
        .set(RECURRING.SOURCE, "auto")
        .execute();
  }

  @Test
  void includesOnlyConfirmedRecurringPredominantlyDebitCounterparties() {
    long imp = importId();

    // (A) confirmed + recurring + DBIT -> included.
    insertTxn(imp, "hash-a1", LocalDate.now().minusDays(5), "10.00", "DBIT", "CDTR-A", null, "Included Co");
    insertTxn(imp, "hash-a2", LocalDate.now().minusDays(35), "10.00", "DBIT", "CDTR-A", null, "Included Co");

    // (B) confirmed + recurring but predominant CRDT -> excluded.
    insertTxn(imp, "hash-b1", LocalDate.now().minusDays(5), "10.00", "CRDT", "CDTR-B", null, "Credit Co");
    insertTxn(imp, "hash-b2", LocalDate.now().minusDays(35), "10.00", "CRDT", "CDTR-B", null, "Credit Co");
    insertTxn(imp, "hash-b3", LocalDate.now().minusDays(65), "10.00", "CRDT", "CDTR-B", null, "Credit Co");

    // (C) open + recurring + DBIT -> excluded (not confirmed).
    insertTxn(imp, "hash-c1", LocalDate.now().minusDays(5), "999.00", "DBIT", "CDTR-C", null, "Open Co");
    insertTxn(imp, "hash-c2", LocalDate.now().minusDays(35), "999.00", "DBIT", "CDTR-C", null, "Open Co");

    resolver.run(null);

    long idA = counterpartyIdFor("CDTR-A");
    long idB = counterpartyIdFor("CDTR-B");
    long idC = counterpartyIdFor("CDTR-C");

    confirm(idA);
    confirm(idB);
    // idC stays open.

    insertRecurring(idA, "monthly", "10.00");
    insertRecurring(idB, "monthly", "10.00");
    insertRecurring(idC, "monthly", "999.00");

    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, idA)
        .set(COUNTERPARTY_TAGS.DIMENSION, "domain")
        .set(COUNTERPARTY_TAGS.VALUE, "insurance")
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .execute();

    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, idA)
        .set(CONTRACTS.STATUS, "linked")
        .set(CONTRACTS.HIVEMEM_CELL_ID, "cell-a")
        .execute();

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    ObligationRow row = register.rows().get(0);
    assertThat(row.counterpartyId()).isEqualTo(idA);
    assertThat(row.displayName()).isEqualTo("Included Co");
    assertThat(row.annualCost()).isEqualByComparingTo("120.00");
    assertThat(row.tags()).extracting(CounterpartyTagView::value).containsExactly("insurance");
    assertThat(row.hasContract()).isTrue();
    assertThat(row.hivememCellId()).isEqualTo("cell-a");

    assertThat(register.totalAnnualCost()).isEqualByComparingTo("120.00");
  }

  @Test
  void mixedDirectionCounterpartyUsesDebitOnlyEvidenceNeverTheRefund() {
    long imp = importId();

    // 11 DBIT bookings of 20 + 1 CRDT refund of 500 -> predominant direction is DBIT (mode).
    for (int i = 0; i < 11; i++) {
      insertTxn(
          imp,
          "hash-mix-dbit-" + i,
          LocalDate.now().minusDays(10L * (i + 1)),
          "20.00",
          "DBIT",
          "CDTR-MIX",
          null,
          "Mixed Co");
    }
    insertTxn(imp, "hash-mix-crdt", LocalDate.now().minusDays(3), "500.00", "CRDT", "CDTR-MIX", null, "Mixed Co");

    resolver.run(null);

    long idMix = counterpartyIdFor("CDTR-MIX");
    confirm(idMix);
    // No cadence recorded (irregular) so AnnualCost falls back to debit_last_365d.
    insertRecurring(idMix, "irregular", null);

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    ObligationRow row = register.rows().get(0);
    assertThat(row.counterpartyId()).isEqualTo(idMix);
    assertThat(row.annualCost()).isEqualByComparingTo("220.00"); // 11 * 20.00, refund excluded.
    assertThat(register.totalAnnualCost()).isEqualByComparingTo("220.00");
  }

  @Test
  void rowsAreSortedByAnnualCostDescendingWithMatchingTotal() {
    long imp = importId();

    insertTxn(imp, "hash-low1", LocalDate.now().minusDays(5), "5.00", "DBIT", "CDTR-LOW", null, "Low Co");
    insertTxn(imp, "hash-low2", LocalDate.now().minusDays(35), "5.00", "DBIT", "CDTR-LOW", null, "Low Co");

    insertTxn(imp, "hash-high1", LocalDate.now().minusDays(5), "100.00", "DBIT", "CDTR-HIGH", null, "High Co");
    insertTxn(imp, "hash-high2", LocalDate.now().minusDays(35), "100.00", "DBIT", "CDTR-HIGH", null, "High Co");

    resolver.run(null);

    long idLow = counterpartyIdFor("CDTR-LOW");
    long idHigh = counterpartyIdFor("CDTR-HIGH");
    confirm(idLow);
    confirm(idHigh);
    insertRecurring(idLow, "monthly", "5.00");
    insertRecurring(idHigh, "monthly", "100.00");

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(2);
    assertThat(register.rows().get(0).displayName()).isEqualTo("High Co");
    assertThat(register.rows().get(1).displayName()).isEqualTo("Low Co");
    BigDecimal expectedTotal = new BigDecimal("1200.00").add(new BigDecimal("60.00"));
    assertThat(register.totalAnnualCost()).isEqualByComparingTo(expectedTotal);
  }
}
