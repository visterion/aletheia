package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
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
 * Contract-grain P1 cases for {@link ReadTools#obligationsRegister} and {@link
 * ReadTools#getReviewQueue} (spec review M1: a split counterparty's contracts must each carry
 * their OWN annual cost, never the counterparty's combined debit).
 */
class RegisterToolsContractGrainIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired ReadTools readTools;
  @Autowired WriteTools writeTools;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;

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
      String mandateId,
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

  private void resolveAll() {
    counterpartyResolver.run(null);
    contractResolver.run(null);
  }

  private Long contractIdFor(long counterpartyId, String mandateId) {
    return db.select(CONTRACTS.ID)
        .from(CONTRACTS)
        .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
        .and(mandateId == null ? CONTRACTS.MANDATE_ID.isNull() : CONTRACTS.MANDATE_ID.eq(mandateId))
        .fetchOne(CONTRACTS.ID);
  }

  /** Seeds a two-mandate counterparty (Debeka), each mandate meeting the >=2-month rule. */
  private long seedTwoContractCounterparty() {
    long imp = importId();
    // Contract A (MANDATE-1): 2 debits of 30.00 in distinct months -> debit_last_365d = 60.00.
    insertTxn(imp, "hash-deb-a1", LocalDate.now().minusDays(5), "30.00", "DBIT", "CDTR-DEBEKA", "MANDATE-1", "Debeka");
    insertTxn(imp, "hash-deb-a2", LocalDate.now().minusDays(35), "30.00", "DBIT", "CDTR-DEBEKA", "MANDATE-1", "Debeka");
    // Contract B (MANDATE-2): 2 debits of 50.00 in distinct months -> debit_last_365d = 100.00.
    insertTxn(imp, "hash-deb-b1", LocalDate.now().minusDays(8), "50.00", "DBIT", "CDTR-DEBEKA", "MANDATE-2", "Debeka");
    insertTxn(imp, "hash-deb-b2", LocalDate.now().minusDays(38), "50.00", "DBIT", "CDTR-DEBEKA", "MANDATE-2", "Debeka");

    resolveAll();
    return counterpartyIdFor("CDTR-DEBEKA");
  }

  @Test
  void twoContractCounterpartyProducesTwoRegisterRowsTotalIsTheirSum() {
    long debekaId = seedTwoContractCounterparty();
    long contractA = contractIdFor(debekaId, "MANDATE-1");
    long contractB = contractIdFor(debekaId, "MANDATE-2");

    writeTools.confirmCounterparty(debekaId, contractA);
    writeTools.confirmCounterparty(debekaId, contractB);

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(2);
    assertThat(register.rows())
        .extracting(ObligationRow::contractId)
        .containsExactlyInAnyOrder(contractA, contractB);

    BigDecimal expectedTotal = new BigDecimal("60.00").add(new BigDecimal("100.00"));
    assertThat(register.totalAnnualCost()).isEqualByComparingTo(expectedTotal);
    BigDecimal sumOfRows =
        register.rows().stream().map(ObligationRow::annualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sumOfRows).isEqualByComparingTo(expectedTotal);
  }

  @Test
  void irregularContractCostIsItsOwnDebitNeverTheCounterpartyCombinedDebit() {
    long debekaId = seedTwoContractCounterparty();
    long contractA = contractIdFor(debekaId, "MANDATE-1");
    long contractB = contractIdFor(debekaId, "MANDATE-2");

    writeTools.confirmCounterparty(debekaId, contractA);
    writeTools.confirmCounterparty(debekaId, contractB);

    ObligationsRegister register = readTools.obligationsRegister();

    ObligationRow rowA =
        register.rows().stream().filter(r -> r.contractId() == contractA).findFirst().orElseThrow();
    ObligationRow rowB =
        register.rows().stream().filter(r -> r.contractId() == contractB).findFirst().orElseThrow();

    // Combined counterparty debit is 160.00 -- neither row may show that; each shows its own.
    assertThat(rowA.cadence()).isEqualTo("irregular");
    assertThat(rowA.annualCost()).isEqualByComparingTo("60.00");
    assertThat(rowB.cadence()).isEqualTo("irregular");
    assertThat(rowB.annualCost()).isEqualByComparingTo("100.00");
  }

  @Test
  void statusGateOnlyConfirmedContractAppearsCounterpartyStatusIrrelevant() {
    long debekaId = seedTwoContractCounterparty();
    long contractA = contractIdFor(debekaId, "MANDATE-1");
    long contractB = contractIdFor(debekaId, "MANDATE-2");

    writeTools.confirmCounterparty(debekaId, contractA);
    // contractB stays open; counterparties.status is never flipped by the per-contract confirm.

    assertThat(
            db.select(COUNTERPARTIES.STATUS)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.ID.eq(debekaId))
                .fetchOne(COUNTERPARTIES.STATUS))
        .isEqualTo("open");

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    assertThat(register.rows().get(0).contractId()).isEqualTo(contractA);
  }

  @Test
  void mandatelessConfirmedObligationAppearsInTheRegisterUsingCounterpartyDebitFallback() {
    long imp = importId();
    // No mandate_id at all -> ContractResolver never creates a contract automatically.
    insertTxn(imp, "hash-ml-1", LocalDate.now().minusDays(10), "15.00", "DBIT", "CDTR-MANDATELESS", null, "Mandateless Co");
    insertTxn(imp, "hash-ml-2", LocalDate.now().minusDays(40), "15.00", "DBIT", "CDTR-MANDATELESS", null, "Mandateless Co");
    resolveAll();

    long id = counterpartyIdFor("CDTR-MANDATELESS");
    assertThat(contractIdFor(id, null)).isNull();

    // Irregular, no typicalAmount -> AnnualCost must fall back to the per-contract debit; since
    // the materialized contract's mandate_id is NULL, v_contract_evidence has no match, so the
    // fallback must be the counterparty's own v_counterparty_evidence.debit_last_365d (30.00).
    writeTools.markRecurring(id, null, Cadence.irregular, null, null, null, TagSource.auto, null);
    writeTools.confirmCounterparty(id, null);

    Long materializedContractId = contractIdFor(id, null);
    assertThat(materializedContractId).isNotNull();

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    ObligationRow row = register.rows().get(0);
    assertThat(row.contractId()).isEqualTo(materializedContractId);
    assertThat(row.mandateId()).isNull();
    assertThat(row.annualCost()).isEqualByComparingTo("30.00");
  }

  @Test
  void crdtPredominantMandatelessContractShowsZeroAnnualCostNeverTheIncomingAmount() {
    long imp = importId();
    // Predominantly CRDT (incoming, e.g. salary) -- no mandate_id, so ContractResolver never
    // creates a contract automatically. Recurring pattern so it looks confirmable.
    insertTxn(imp, "hash-crdt-1", LocalDate.now().minusDays(10), "2000.00", "CRDT", "CDTR-SALARY", null, "Salary Inc");
    insertTxn(imp, "hash-crdt-2", LocalDate.now().minusDays(40), "2000.00", "CRDT", "CDTR-SALARY", null, "Salary Inc");
    resolveAll();

    long id = counterpartyIdFor("CDTR-SALARY");
    assertThat(contractIdFor(id, null)).isNull();

    // Confirm as a mandate-less obligation -- v_contract_evidence.debit_last_365d and
    // v_counterparty_evidence.debit_last_365d are themselves DBIT-only filtered, so this
    // mistakenly-confirmed CRDT-predominant mandate-less contract must surface as 0.00, never
    // leaking the incoming amount as a cost.
    writeTools.markRecurring(id, null, Cadence.irregular, null, null, null, TagSource.auto, null);
    writeTools.confirmCounterparty(id, null);

    Long materializedContractId = contractIdFor(id, null);
    assertThat(materializedContractId).isNotNull();

    ObligationsRegister register = readTools.obligationsRegister();

    assertThat(register.rows()).hasSize(1);
    ObligationRow row = register.rows().get(0);
    assertThat(row.contractId()).isEqualTo(materializedContractId);
    assertThat(row.annualCost()).isEqualByComparingTo("0.00");
  }

  @Test
  void getReviewQueueListsOpenContractsRankedByAnnualCostDesc() {
    long debekaId = seedTwoContractCounterparty();
    long contractA = contractIdFor(debekaId, "MANDATE-1"); // 60.00, stays open.
    long contractB = contractIdFor(debekaId, "MANDATE-2"); // 100.00, stays open.

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(null, true);

    assertThat(queue)
        .filteredOn(e -> e.contractId() != null)
        .extracting(ReviewQueueEntry::contractId)
        .containsExactly(contractB, contractA);
  }
}
