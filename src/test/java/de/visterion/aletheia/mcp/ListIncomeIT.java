package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

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

    ListPage<IncomeRow> page = readTools.listIncome(new ListParams(null, null, null, true));
    List<IncomeRow> income = page.rows();

    assertThat(income).hasSize(2);
    assertThat(income.get(0).displayName()).isEqualTo("Salary Co");
    assertThat(income.get(0).creditTotal()).isEqualByComparingTo("6000.00");
    assertThat(income.get(0).creditLast365d()).isEqualByComparingTo("6000.00");
    assertThat(income.get(0).txnCount()).isEqualTo(2L);
    assertThat(income.get(1).displayName()).isEqualTo("Family Co");
    assertThat(income.get(1).creditTotal()).isEqualByComparingTo("100.00");

    assertThat(income).extracting(IncomeRow::displayName).doesNotContain("Rent Co");
  }

  @Test
  void limitAndOffsetPageDeterministicallyAcrossTiedRows() {
    // Three CRDT counterparties with identical credit_total: without the id tie-breaker Postgres
    // may order them differently per query, so a row could appear on two pages and another on none.
    seedCreditCounterparty("TIE A", "100.00");
    seedCreditCounterparty("TIE B", "100.00");
    seedCreditCounterparty("TIE C", "100.00");

    List<Long> walked = new ArrayList<>();
    for (int offset = 0; offset < 3; offset++) {
      ListPage<IncomeRow> page = readTools.listIncome(new ListParams(1, offset, null, true));
      assertThat(page.rows()).hasSize(1);
      walked.add(page.rows().getFirst().counterpartyId());
    }
    assertThat(walked).doesNotHaveDuplicates().hasSize(3);
  }

  @Test
  void metaReportsTheUnpagedTotalSoTruncationIsVisible() {
    seedCreditCounterparty("META A", "300.00");
    seedCreditCounterparty("META B", "200.00");
    seedCreditCounterparty("META C", "100.00");

    ListPage<IncomeRow> page = readTools.listIncome(new ListParams(2, 0, null, true));

    assertThat(page.rows()).hasSize(2);
    assertThat(page.meta().rowsReturned()).isEqualTo(2);
    assertThat(page.meta().rowsTotal()).isGreaterThanOrEqualTo(3);
    assertThat(page.meta().rowsTotal()).isGreaterThan(page.meta().rowsReturned());
    assertThat(page.meta().limit()).isEqualTo(2);
    assertThat(page.meta().offset()).isZero();
    assertThat(page.meta().minAmount()).isNull();
  }

  @Test
  void minAmountFiltersInclusivelyAndIsCountedBeforePaging() {
    seedCreditCounterparty("MIN LOW", "1.00");
    seedCreditCounterparty("MIN EXACT", "50.00");
    seedCreditCounterparty("MIN HIGH", "500.00");

    ListPage<IncomeRow> page =
        readTools.listIncome(new ListParams(null, null, new BigDecimal("50.00"), true));

    assertThat(page.rows()).extracting(IncomeRow::displayName).contains("MIN EXACT", "MIN HIGH");
    assertThat(page.rows()).extracting(IncomeRow::displayName).doesNotContain("MIN LOW");
    assertThat(page.meta().minAmount()).isEqualByComparingTo("50.00");
    assertThat(page.meta().rowsTotal()).isEqualTo(page.rows().size());
  }

  @Test
  void offsetPastTheEndYieldsNoRowsButKeepsTheTotal() {
    seedCreditCounterparty("PAST END", "42.00");

    ListPage<IncomeRow> page = readTools.listIncome(new ListParams(10, 10_000, null, true));

    assertThat(page.rows()).isEmpty();
    assertThat(page.meta().rowsReturned()).isZero();
    assertThat(page.meta().rowsTotal()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void compactModeOmitsIdentityTypeAndFirstSeenFromTheSerializedJson() throws Exception {
    seedCreditCounterparty("COMPACT", "77.00");

    ListPage<IncomeRow> compact =
        readTools.listIncome(new ListParams(null, null, new BigDecimal("77.00"), false));
    ListPage<IncomeRow> verbose =
        readTools.listIncome(new ListParams(null, null, new BigDecimal("77.00"), true));

    // Assert on the parsed node, not the raw string: a substring check would pass on a row that
    // merely lacks the value while still emitting the key.
    var compactRow = new ObjectMapper().valueToTree(compact.rows().getFirst());
    var verboseRow = new ObjectMapper().valueToTree(verbose.rows().getFirst());

    assertThat(compactRow.has("identityType")).isFalse();
    assertThat(compactRow.has("firstSeen")).isFalse();
    assertThat(compactRow.has("creditTotal")).isTrue();
    assertThat(verboseRow.has("identityType")).isTrue();
    assertThat(verboseRow.has("firstSeen")).isTrue();
  }

  private void seedCreditCounterparty(String displayName, String amount) {
    long imp = importId();
    insertTxn(
        imp,
        "hash-" + UUID.randomUUID(),
        LocalDate.now().minusDays(30),
        amount,
        "CRDT",
        null,
        null,
        displayName);
    resolver.run(null);
  }
}
