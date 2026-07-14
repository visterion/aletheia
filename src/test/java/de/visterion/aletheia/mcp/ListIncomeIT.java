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

class ListIncomeIT extends AbstractPostgresIT {

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
  void listIncomeReturnsOnlyCrdtPredominantCounterpartiesOrderedByCreditTotalDesc() {
    long imp = importId();
    // Salary Co: predominantly CRDT, large credit total.
    insertTxn(imp, "hash-sal-1", LocalDate.now().minusDays(10), "3000.00", "CRDT", "CDTR-SALARY", null, "Salary Co");
    insertTxn(imp, "hash-sal-2", LocalDate.now().minusDays(40), "3000.00", "CRDT", "CDTR-SALARY", null, "Salary Co");
    // Rent Co: predominantly DBIT -- must not appear in list_income.
    insertTxn(imp, "hash-rent-1", LocalDate.now().minusDays(5), "800.00", "DBIT", "CDTR-RENT", null, "Rent Co");
    // Family Co: predominantly CRDT, smaller credit total than Salary Co.
    insertTxn(imp, "hash-fam-1", LocalDate.now().minusDays(15), "100.00", "CRDT", "CDTR-FAMILY", null, "Family Co");

    resolver.run(null);

    List<IncomeRow> income = readTools.listIncome();

    assertThat(income).hasSize(2);
    assertThat(income.get(0).displayName()).isEqualTo("Salary Co");
    assertThat(income.get(0).creditTotal()).isEqualByComparingTo("6000.00");
    assertThat(income.get(0).creditLast365d()).isEqualByComparingTo("6000.00");
    assertThat(income.get(0).txnCount()).isEqualTo(2L);
    assertThat(income.get(1).displayName()).isEqualTo("Family Co");
    assertThat(income.get(1).creditTotal()).isEqualByComparingTo("100.00");

    assertThat(income).extracting(IncomeRow::displayName).doesNotContain("Rent Co");
  }
}
