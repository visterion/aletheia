package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 5 (Spec B, contract-lifecycle): {@code end_contract} moves a confirmed contract to {@code
 * status='ended'} + {@code end_date} set, taking it out of the obligations register and the
 * unmatched-recurring list while keeping its history. Confirming the counterparty again reopens
 * an {@code ended} mandate-less contract -- but never a {@code dismissed} one (F-1 regression).
 */
class EndContractIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ContractResolver contractResolver;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparty_alias, counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private long counterpartyWithOneTransaction(String creditorId, String name) {
    long imp = importId();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "hash-" + UUID.randomUUID())
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now())
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
    resolver.run(null);
    return db.select(COUNTERPARTIES.ID)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_VALUE.eq(creditorId))
        .fetchOne(COUNTERPARTIES.ID);
  }

  private long seedContract(long counterpartyId, String mandateId, String status) {
    return db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
        .set(CONTRACTS.MANDATE_ID, mandateId)
        .set(CONTRACTS.SOURCE, "auto")
        .set(CONTRACTS.STATUS, status)
        .returning(CONTRACTS.ID)
        .fetchOne(CONTRACTS.ID);
  }

  private void seedRecurring(long counterpartyId, Long contractId, String source, String amount) {
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
        .set(RECURRING.CONTRACT_ID, contractId)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal(amount))
        .set(RECURRING.SOURCE, source)
        .execute();
  }

  private Record contractRow(long contractId) {
    return db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractId)).fetchOne();
  }

  // --- end confirmed contract ---

  @Test
  void endsAConfirmedContractAndRemovesItFromTheRegisterAndUnmatchedRecurring() {
    long id = counterpartyWithOneTransaction("CDTR-END-1", "End Co");
    long contractA = seedContract(id, "MANDATE-END-1", "confirmed");
    seedRecurring(id, contractA, "confirmed", "9.99");

    WriteAck ack = writeTools.endContract(contractA, null, "cancelled by user");

    assertThat(ack.counterpartyId()).isEqualTo(id);

    Record row = contractRow(contractA);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("ended");
    assertThat(row.get(CONTRACTS.END_DATE)).isEqualTo(LocalDate.now());

    Record history =
        db.selectFrom(COUNTERPARTY_HISTORY)
            .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id))
            .and(COUNTERPARTY_HISTORY.FIELD.eq("contract:" + contractA))
            .orderBy(COUNTERPARTY_HISTORY.ID.desc())
            .limit(1)
            .fetchOne();
    assertThat(history).isNotNull();
    assertThat(history.get(COUNTERPARTY_HISTORY.OLD_VALUE)).isEqualTo("confirmed");
    assertThat(history.get(COUNTERPARTY_HISTORY.NEW_VALUE)).isEqualTo("ended");

    ObligationsRegister register = readTools.obligationsRegister();
    assertThat(register.rows()).noneMatch(r -> r.contractId() == contractA);
    assertThat(register.totalAnnualCost()).isEqualByComparingTo(BigDecimal.ZERO);

    var unmatched = readTools.listUnmatchedRecurring(null, null);
    assertThat(unmatched).noneMatch(e -> Long.valueOf(contractA).equals(e.contractId()));
  }

  @Test
  void endingAnOpenContractIsRejectedWithNoMutation() {
    long id = counterpartyWithOneTransaction("CDTR-END-OPEN", "Open Co");
    long contractA = seedContract(id, "MANDATE-END-OPEN", "open");

    assertThatThrownBy(() -> writeTools.endContract(contractA, null, "nope"))
        .isInstanceOf(IllegalArgumentException.class);

    Record row = contractRow(contractA);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("open");
    assertThat(row.get(CONTRACTS.END_DATE)).isNull();
  }

  @Test
  void endingADismissedContractIsRejectedWithNoMutation() {
    long id = counterpartyWithOneTransaction("CDTR-END-DISMISSED", "Dismissed Co");
    long contractA = seedContract(id, "MANDATE-END-DISMISSED", "dismissed");

    assertThatThrownBy(() -> writeTools.endContract(contractA, null, "nope"))
        .isInstanceOf(IllegalArgumentException.class);

    Record row = contractRow(contractA);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("dismissed");
    assertThat(row.get(CONTRACTS.END_DATE)).isNull();
  }

  @Test
  void endContractRejectsANonexistentContractId() {
    assertThatThrownBy(() -> writeTools.endContract(999_999L, null, "nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- endDate default/explicit ---

  @Test
  void defaultsEndDateToTodayWhenOmitted() {
    long id = counterpartyWithOneTransaction("CDTR-END-DEFAULT-DATE", "Default Date Co");
    long contractA = seedContract(id, "MANDATE-END-DEFAULT-DATE", "confirmed");

    writeTools.endContract(contractA, null, null);

    assertThat(contractRow(contractA).get(CONTRACTS.END_DATE)).isEqualTo(LocalDate.now());
  }

  @Test
  void honorsAnExplicitEndDate() {
    long id = counterpartyWithOneTransaction("CDTR-END-EXPLICIT-DATE", "Explicit Date Co");
    long contractA = seedContract(id, "MANDATE-END-EXPLICIT-DATE", "confirmed");
    LocalDate explicit = LocalDate.of(2026, 6, 30);

    writeTools.endContract(contractA, explicit, null);

    assertThat(contractRow(contractA).get(CONTRACTS.END_DATE)).isEqualTo(explicit);
  }

  // --- folded counterparty guard ---

  @Test
  void rejectsEndingAContractOnAFoldedCounterparty() {
    long a = counterpartyWithOneTransaction("CDTR-END-FOLD-A", "Fold Target Co");
    long b = counterpartyWithOneTransaction("CDTR-END-FOLD-B", "Fold Source Co");
    long contractOfB = seedContract(b, "MANDATE-END-FOLD-B", "confirmed");

    db.execute(
        "INSERT INTO counterparty_alias (identity_type, identity_value, canonical_counterparty_id) "
            + "VALUES ('creditor_id', 'CDTR-END-FOLD-B', ?)",
        a);
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.MERGED_INTO, a)
        .where(COUNTERPARTIES.ID.eq(b))
        .execute();

    assertThatThrownBy(() -> writeTools.endContract(contractOfB, null, "nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");

    Record row = contractRow(contractOfB);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
  }

  // --- re-ingest stability ---

  @Test
  void anEndedContractStaysEndedAfterAResolverRun() {
    long id = counterpartyWithOneTransaction("CDTR-END-REINGEST", "Reingest Co");
    long contractA = seedContract(id, "MANDATE-END-REINGEST", "confirmed");
    seedRecurring(id, contractA, "confirmed", "9.99");

    writeTools.endContract(contractA, null, "cancelled");
    contractResolver.resolve();

    Record row = contractRow(contractA);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("ended");
    assertThat(row.get(CONTRACTS.END_DATE)).isNotNull();
  }

  // --- reopen (implicit, mandate-less) ---

  @Test
  void confirmingTheCounterpartyReopensAnEndedMandatelessContractWithoutDuplicating() {
    long id = counterpartyWithOneTransaction("CDTR-REOPEN-IMPLICIT", "Reopen Implicit Co");
    seedRecurring(id, null, "auto", "5.00");

    // materialize + confirm the mandate-less contract
    writeTools.confirmCounterparty(id, null, null, null, null);
    long contractId =
        db.select(CONTRACTS.ID)
            .from(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(id))
            .and(CONTRACTS.MANDATE_ID.isNull())
            .fetchOne(CONTRACTS.ID);

    writeTools.endContract(contractId, null, "cancelled for now");
    assertThat(contractRow(contractId).get(CONTRACTS.STATUS)).isEqualTo("ended");

    writeTools.confirmCounterparty(id, null, null, null, null);

    var contracts =
        db.selectFrom(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(id))
            .and(CONTRACTS.MANDATE_ID.isNull())
            .fetch();
    assertThat(contracts).hasSize(1); // no duplicate contract row
    Record reopened = contracts.get(0);
    assertThat(reopened.get(CONTRACTS.ID)).isEqualTo(contractId);
    assertThat(reopened.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
    assertThat(reopened.get(CONTRACTS.END_DATE)).isNull();

    var history =
        db.selectFrom(COUNTERPARTY_HISTORY)
            .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id))
            .and(COUNTERPARTY_HISTORY.FIELD.eq("contract:" + contractId))
            .fetch();
    assertThat(history)
        .anyMatch(
            h ->
                "ended".equals(h.get(COUNTERPARTY_HISTORY.OLD_VALUE))
                    && "confirmed".equals(h.get(COUNTERPARTY_HISTORY.NEW_VALUE)));
  }

  // --- reopen (explicit contractId) ---

  @Test
  void explicitConfirmCounterpartyWithTheEndedContractIdReopensIt() {
    long id = counterpartyWithOneTransaction("CDTR-REOPEN-EXPLICIT", "Reopen Explicit Co");
    long contractA = seedContract(id, "MANDATE-REOPEN-EXPLICIT", "confirmed");
    seedRecurring(id, contractA, "confirmed", "9.99");

    writeTools.endContract(contractA, null, "cancelled");
    assertThat(contractRow(contractA).get(CONTRACTS.STATUS)).isEqualTo("ended");

    writeTools.confirmCounterparty(id, contractA, null, null, null);

    Record row = contractRow(contractA);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
    assertThat(row.get(CONTRACTS.END_DATE)).isNull();
  }

  // --- F-1 regression: dismissed mandate-less contract must NOT be reopened ---

  @Test
  void confirmingTheCounterpartyDoesNotResurrectADismissedMandatelessContract() {
    long id = counterpartyWithOneTransaction("CDTR-F1-SINGLE", "F1 Single Co");
    seedRecurring(id, null, "auto", "5.00");

    writeTools.dismissCounterparty(id, null, null, null, "not wanted", null);
    long contractId =
        db.select(CONTRACTS.ID)
            .from(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(id))
            .and(CONTRACTS.MANDATE_ID.isNull())
            .fetchOne(CONTRACTS.ID);
    assertThat(contractRow(contractId).get(CONTRACTS.STATUS)).isEqualTo("dismissed");

    writeTools.confirmCounterparty(id, null, null, null, null);

    Record row = contractRow(contractId);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("dismissed");

    ObligationsRegister register = readTools.obligationsRegister();
    assertThat(register.rows()).noneMatch(r -> Long.valueOf(contractId).equals(r.contractId()));
  }

  @Test
  void batchConfirmDoesNotResurrectADismissedMandatelessContract() {
    long id = counterpartyWithOneTransaction("CDTR-F1-BATCH", "F1 Batch Co");
    seedRecurring(id, null, "auto", "5.00");

    writeTools.dismissCounterparty(id, null, null, null, "not wanted", null);
    long contractId =
        db.select(CONTRACTS.ID)
            .from(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(id))
            .and(CONTRACTS.MANDATE_ID.isNull())
            .fetchOne(CONTRACTS.ID);
    assertThat(contractRow(contractId).get(CONTRACTS.STATUS)).isEqualTo("dismissed");

    writeTools.confirmCounterparty(null, null, java.util.List.of(id), null, null);

    Record row = contractRow(contractId);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("dismissed");

    ObligationsRegister register = readTools.obligationsRegister();
    assertThat(register.rows()).noneMatch(r -> Long.valueOf(contractId).equals(r.contractId()));
  }
}
