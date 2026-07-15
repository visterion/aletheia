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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  private long counterparty(String identityValue, String displayName) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "creditor_id")
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, displayName)
        .set(COUNTERPARTIES.STATUS, "open")
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private long contract(long counterpartyId, String mandateId, String status) {
    return db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
        .set(CONTRACTS.MANDATE_ID, mandateId)
        .set(CONTRACTS.STATUS, status)
        .returning(CONTRACTS.ID)
        .fetchOne(CONTRACTS.ID);
  }

  private long confirmedContract(long counterpartyId) {
    return confirmedContract(counterpartyId, null);
  }

  private long confirmedContract(long counterpartyId, String mandateId) {
    return contract(counterpartyId, mandateId, "confirmed");
  }

  private void insertTag(long counterpartyId, String dimension, String value, String source) {
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, counterpartyId)
        .set(COUNTERPARTY_TAGS.DIMENSION, dimension)
        .set(COUNTERPARTY_TAGS.VALUE, value)
        .set(COUNTERPARTY_TAGS.SOURCE, source)
        .execute();
  }

  private void insertRecurring(
      long counterpartyId, Long contractId, String cadence, String typicalAmount) {
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
        .set(RECURRING.CONTRACT_ID, contractId)
        .set(RECURRING.CADENCE, cadence)
        .set(RECURRING.TYPICAL_AMOUNT, typicalAmount == null ? null : new BigDecimal(typicalAmount))
        .set(RECURRING.SOURCE, "auto")
        .execute();
  }

  @Test
  void excludesConfirmedPaymentServiceContractsFromRowsAndTotal() {
    long normalId = counterparty("NORMAL", "Normal Contract");
    long normalContract = confirmedContract(normalId);
    insertRecurring(normalId, normalContract, "monthly", "10.00");

    long paymentId = counterparty("PAYMENT", "Payment Service");
    long paymentContractA = confirmedContract(paymentId, "MANDATE-PAYMENT-A");
    long paymentContractB = confirmedContract(paymentId, "MANDATE-PAYMENT-B");
    insertRecurring(paymentId, paymentContractA, "monthly", "50.00");
    insertRecurring(paymentId, paymentContractB, "monthly", "75.00");
    insertTag(paymentId, "nature", "zahlungsdienst", "confirmed");

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows())
        .extracting(ObligationRow::contractId)
        .containsExactly(normalContract)
        .doesNotContain(paymentContractA, paymentContractB);
    assertThat(register.totalAnnualCost()).isEqualByComparingTo("120.00");
  }

  @Test
  void onlyConfirmedPaymentServiceContractsProduceEmptyRegisterAndZeroTotal() {
    long paymentId = counterparty("PAYMENT-ONLY", "Payment Service Only");
    long paymentContract = confirmedContract(paymentId);
    insertRecurring(paymentId, paymentContract, "monthly", "50.00");
    insertTag(paymentId, "nature", "zahlungsdienst", "confirmed");

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).isEmpty();
    assertThat(register.totalAnnualCost()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @ParameterizedTest
  @CsvSource({
    "nature, zahlungsdienst, auto",
    "domain, zahlungsdienst, confirmed",
    "nature, versicherung, confirmed",
    "nature, Zahlungsdienst, confirmed"
  })
  void nearMatchPaymentServiceTagsDoNotExcludeConfirmedContract(
      String dimension, String value, String source) {
    long counterpartyId =
        counterparty("NEAR-" + dimension + "-" + value + "-" + source, "Near Match");
    long contractId = confirmedContract(counterpartyId);
    insertRecurring(counterpartyId, contractId, "monthly", "10.00");
    insertTag(counterpartyId, dimension, value, source);

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).extracting(ObligationRow::contractId).containsExactly(contractId);
    assertThat(register.totalAnnualCost()).isEqualByComparingTo("120.00");
  }

  @Test
  void confirmedPaymentServiceTagsDoNotAffectEitherReviewQueueBranch() {
    long imp = importId();
    insertTxn(
        imp,
        "hash-queue-contract",
        LocalDate.now().minusDays(5),
        "10.00",
        "DBIT",
        "QUEUE-CONTRACT",
        null,
        "Queued Contract");
    insertTxn(
        imp,
        "hash-queue-counterparty",
        LocalDate.now().minusDays(5),
        "10.00",
        "DBIT",
        "QUEUE-COUNTERPARTY",
        null,
        "Queued Counterparty");

    long contractCounterpartyId = counterparty("QUEUE-CONTRACT", "Queued Contract");
    long openContractId = contract(contractCounterpartyId, "MANDATE-QUEUE", "open");
    insertTag(contractCounterpartyId, "nature", "zahlungsdienst", "confirmed");

    long noContractCounterpartyId = counterparty("QUEUE-COUNTERPARTY", "Queued Counterparty");
    insertTag(noContractCounterpartyId, "nature", "zahlungsdienst", "confirmed");

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, false);

    assertThat(queue)
        .anySatisfy(
            entry -> {
              assertThat(entry.id()).isEqualTo(contractCounterpartyId);
              assertThat(entry.contractId()).isEqualTo(openContractId);
            })
        .anySatisfy(
            entry -> {
              assertThat(entry.id()).isEqualTo(noContractCounterpartyId);
              assertThat(entry.contractId()).isNull();
            });
  }

  @Test
  void includesOnlyConfirmedContracts() {
    long imp = importId();

    // (A) confirmed contract + recurring + DBIT -> included.
    insertTxn(imp, "hash-a1", LocalDate.now().minusDays(5), "10.00", "DBIT", "CDTR-A", null, "Included Co");
    insertTxn(imp, "hash-a2", LocalDate.now().minusDays(35), "10.00", "DBIT", "CDTR-A", null, "Included Co");

    // (B) no contract row at all -> excluded, even though the counterparty is confirmed.
    insertTxn(imp, "hash-b1", LocalDate.now().minusDays(5), "10.00", "CRDT", "CDTR-B", null, "Credit Co");
    insertTxn(imp, "hash-b2", LocalDate.now().minusDays(35), "10.00", "CRDT", "CDTR-B", null, "Credit Co");
    insertTxn(imp, "hash-b3", LocalDate.now().minusDays(65), "10.00", "CRDT", "CDTR-B", null, "Credit Co");

    // (C) no contract row at all -> excluded.
    insertTxn(imp, "hash-c1", LocalDate.now().minusDays(5), "999.00", "DBIT", "CDTR-C", null, "Open Co");
    insertTxn(imp, "hash-c2", LocalDate.now().minusDays(35), "999.00", "DBIT", "CDTR-C", null, "Open Co");

    resolver.run(null);

    long idA = counterpartyIdFor("CDTR-A");
    long idB = counterpartyIdFor("CDTR-B");
    long idC = counterpartyIdFor("CDTR-C");

    long contractA = confirmedContract(idA);
    // idB, idC get no contract row at all -> never appear in the register.

    insertRecurring(idA, contractA, "monthly", "10.00");
    // B and C have no contract to attach recurring to for this test's purposes -- irrelevant,
    // since only a confirmed CONTRACTS row roots a register row.

    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, idA)
        .set(COUNTERPARTY_TAGS.DIMENSION, "domain")
        .set(COUNTERPARTY_TAGS.VALUE, "insurance")
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .execute();

    db.update(CONTRACTS)
        .set(CONTRACTS.HIVEMEM_CELL_ID, "cell-a")
        .where(CONTRACTS.ID.eq(contractA))
        .execute();

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    ObligationRow row = register.rows().get(0);
    assertThat(row.counterpartyId()).isEqualTo(idA);
    assertThat(row.contractId()).isEqualTo(contractA);
    assertThat(row.mandateId()).isNull();
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
    long contractMix = confirmedContract(idMix);
    // No cadence recorded (irregular) so AnnualCost falls back to the mandate-less contract's
    // debit fallback, which is the counterparty's own debit_last_365d (v_contract_evidence has
    // no match for a NULL mandate_id).
    insertRecurring(idMix, contractMix, "irregular", null);

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
    long contractLow = confirmedContract(idLow);
    long contractHigh = confirmedContract(idHigh);
    insertRecurring(idLow, contractLow, "monthly", "5.00");
    insertRecurring(idHigh, contractHigh, "monthly", "100.00");

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(2);
    assertThat(register.rows().get(0).displayName()).isEqualTo("High Co");
    assertThat(register.rows().get(1).displayName()).isEqualTo("Low Co");
    BigDecimal expectedTotal = new BigDecimal("1200.00").add(new BigDecimal("60.00"));
    assertThat(register.totalAnnualCost()).isEqualByComparingTo(expectedTotal);
  }

  @Test
  void obligationsRegisterUsesLogicalViewHidesSplitParentsChildrenProvideCorrectDebit() {
    // Covers obligations_register + openContractEntries path (via v_contract_evidence +
    // v_counterparty fallback) and that list in ReadToolsIT covers get_review_queue helpers.
    long imp = importId();
    String parentHash = "parent-for-obl-001";
    LocalDate d = LocalDate.now().minusDays(10);
    // Parent with mandate (to exercise v_contract_evidence path)
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, parentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, d)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("100.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "CDTR-OBL-SPLIT")
        .set(TRANSACTIONS.MANDATE_ID, "MND-SPLIT")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Obl Split Merchant")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
    // Logical child (purchase part keeps mandate attribution, reduced amount)
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-obl-purch")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, d)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("65.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "CDTR-OBL-SPLIT")
        .set(TRANSACTIONS.MANDATE_ID, "MND-SPLIT")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Obl Split Merchant")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parentHash)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();

    resolver.run(null);

    long cpId = counterpartyIdFor("CDTR-OBL-SPLIT");
    long contractId = db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, cpId)
        .set(CONTRACTS.MANDATE_ID, "MND-SPLIT")
        .set(CONTRACTS.STATUS, "confirmed")
        .set(CONTRACTS.HIVEMEM_CELL_ID, "cell-obl-split")
        .returning(CONTRACTS.ID)
        .fetchOne(CONTRACTS.ID);

    // No recurring -> falls back to debit from (now filtered) v_contract_evidence
    // which must see only the 65 child, not 100 parent + 65.
    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    ObligationRow row = register.rows().get(0);
    assertThat(row.contractId()).isEqualTo(contractId);
    assertThat(row.displayName()).isEqualTo("Obl Split Merchant");
    // Correct data from child only:
    assertThat(row.annualCost()).isEqualByComparingTo("65.00");
    assertThat(register.totalAnnualCost()).isEqualByComparingTo("65.00");
  }
}
