package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import de.visterion.aletheia.substrate.ContractResolver;
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
 * Covers Task 4's per-contract confirm/dismiss/link, lazy materialization of the mandate-less
 * ({@code mandate_id IS NULL}) contract row, and {@code mark_recurring}'s {@code
 * (counterparty_id, contract_id)} rekey + auto-cannot-overwrite-confirmed guard.
 */
class WriteToolsContractIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired WriteTools writeTools;
  @Autowired ContractResolver contractResolver;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long counterpartyWithOneTransaction(String creditorId, String name) {
    long imp =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "synthetic.json")
            .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);
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

  private long seedContract(long counterpartyId, String mandateId) {
    return db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
        .set(CONTRACTS.MANDATE_ID, mandateId)
        .set(CONTRACTS.SOURCE, "auto")
        .set(CONTRACTS.STATUS, "open")
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

  @Test
  void confirmOneContractLeavesTheOtherContractOfTheSameCounterpartyUntouched() {
    long id = counterpartyWithOneTransaction("CDTR-MULTI", "Multi Contract Co");
    long contractA = seedContract(id, "MANDATE-A");
    long contractB = seedContract(id, "MANDATE-B");
    seedRecurring(id, contractA, "auto", "9.99");
    seedRecurring(id, contractB, "auto", "19.99");

    writeTools.confirmCounterparty(id, contractA);

    Record a = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractA)).fetchOne();
    assertThat(a.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
    assertThat(a.get(CONTRACTS.SOURCE)).isEqualTo("confirmed");
    assertThat(a.get(CONTRACTS.CONFIRMED_AT)).isNotNull();

    Record b = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractB)).fetchOne();
    assertThat(b.get(CONTRACTS.STATUS)).isEqualTo("open");

    Record recurringA =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(contractA)).fetchOne();
    assertThat(recurringA.get(RECURRING.SOURCE)).isEqualTo("confirmed");

    Record recurringB =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(contractB)).fetchOne();
    assertThat(recurringB.get(RECURRING.SOURCE)).isEqualTo("auto");
  }

  @Test
  void confirmWithNoContractIdMaterializesTheMandatelessContractWhenAnAutoRecurringRowExists() {
    long id = counterpartyWithOneTransaction("CDTR-MANDATELESS", "Mandateless Co");
    seedRecurring(id, null, "auto", "5.00");

    writeTools.confirmCounterparty(id, null);

    var contracts =
        db.selectFrom(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(id))
            .and(CONTRACTS.MANDATE_ID.isNull())
            .fetch();
    assertThat(contracts).hasSize(1);
    Record contract = contracts.get(0);
    assertThat(contract.get(CONTRACTS.STATUS)).isEqualTo("confirmed");

    Record recurring =
        db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(recurring.get(RECURRING.CONTRACT_ID)).isEqualTo(contract.get(CONTRACTS.ID));
    assertThat(recurring.get(RECURRING.SOURCE)).isEqualTo("confirmed");
  }

  @Test
  void linkContractSetsHivememCellIdOnlyOnTheTargetedContractRow() {
    long id = counterpartyWithOneTransaction("CDTR-LINK", "Link Co");
    long contractA = seedContract(id, "MANDATE-A");
    long contractB = seedContract(id, "MANDATE-B");

    writeTools.linkContract(contractA, "cell-123", "note");

    Record a = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractA)).fetchOne();
    assertThat(a.get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("cell-123");

    Record b = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractB)).fetchOne();
    assertThat(b.get(CONTRACTS.HIVEMEM_CELL_ID)).isNull();
  }

  @Test
  void markRecurringForTwoContractsOfOneCounterpartyDoesNotThrowAndCreatesTwoRows() {
    long id = counterpartyWithOneTransaction("CDTR-TWO-CONTRACTS", "Two Contracts Co");
    long contractA = seedContract(id, "MANDATE-A");
    long contractB = seedContract(id, "MANDATE-B");

    assertThatCode(
            () -> {
              writeTools.markRecurring(
                  id,
                  contractA,
                  Cadence.monthly,
                  new BigDecimal("9.99"),
                  null,
                  null,
                  TagSource.auto,
                  new BigDecimal("0.800"));
              writeTools.markRecurring(
                  id,
                  contractB,
                  Cadence.yearly,
                  new BigDecimal("99.00"),
                  null,
                  null,
                  TagSource.auto,
                  new BigDecimal("0.800"));
            })
        .doesNotThrowAnyException();

    var rows = db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(r -> r.get(RECURRING.CONTRACT_ID)).distinct().count())
        .isEqualTo(2);
  }

  @Test
  void markRecurringWithAutoSourceIsANoOpAgainstAnAlreadyConfirmedRow() {
    long id = counterpartyWithOneTransaction("CDTR-GUARD", "Guard Co");
    long contractA = seedContract(id, "MANDATE-A");
    seedRecurring(id, contractA, "confirmed", "42.00");

    int historyBefore =
        db.fetchCount(
            db.selectFrom(COUNTERPARTY_HISTORY).where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id)));

    writeTools.markRecurring(
        id,
        contractA,
        Cadence.monthly,
        new BigDecimal("1.23"),
        null,
        null,
        TagSource.auto,
        new BigDecimal("0.500"));

    Record row =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(contractA)).fetchOne();
    assertThat(row.get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo("42.00");
    assertThat(row.get(RECURRING.SOURCE)).isEqualTo("confirmed");

    // The guarded upsert performed no update, so no phantom old->new change should have been
    // recorded in counterparty_history.
    int historyAfter =
        db.fetchCount(
            db.selectFrom(COUNTERPARTY_HISTORY).where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id)));
    assertThat(historyAfter).isEqualTo(historyBefore);
  }

  @Test
  void confirmWithNoContractIdDoesNotFlipMandateLinkedRecurringWhenNoMandatelessRowExists() {
    // The Debeka case (final-review Fix #1): a split counterparty whose recurring rows are ALL
    // mandate-linked (no contract_id IS NULL row) must NOT fall into the legacy
    // tag/recurring-wide confirm -- only the per-contract path may confirm mandate contracts.
    long id = counterpartyWithOneTransaction("CDTR-SPLIT-MANDATE", "Split Mandate Co");
    long contractA = seedContract(id, "MANDATE-A");
    long contractB = seedContract(id, "MANDATE-B");
    seedRecurring(id, contractA, "auto", "9.99");
    seedRecurring(id, contractB, "auto", "19.99");

    writeTools.confirmCounterparty(id, null);

    Record recurringA =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(contractA)).fetchOne();
    assertThat(recurringA.get(RECURRING.SOURCE)).isEqualTo("auto");
    Record recurringB =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(contractB)).fetchOne();
    assertThat(recurringB.get(RECURRING.SOURCE)).isEqualTo("auto");

    Record a = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractA)).fetchOne();
    assertThat(a.get(CONTRACTS.STATUS)).isEqualTo("open");
    Record b = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractB)).fetchOne();
    assertThat(b.get(CONTRACTS.STATUS)).isEqualTo("open");
  }

  @Test
  void confirmCounterpartyRejectsAContractBelongingToAnotherCounterparty() {
    long cpA = counterpartyWithOneTransaction("CDTR-OWNER-A", "Owner A Co");
    long cpB = counterpartyWithOneTransaction("CDTR-OWNER-B", "Owner B Co");
    long contractOfB = seedContract(cpB, "MANDATE-B");

    assertThatThrownBy(() -> writeTools.confirmCounterparty(cpA, contractOfB))
        .isInstanceOf(IllegalArgumentException.class);

    Record contract = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractOfB)).fetchOne();
    assertThat(contract.get(CONTRACTS.STATUS)).isEqualTo("open");
    assertThat(
            db.fetchCount(
                db.selectFrom(COUNTERPARTY_HISTORY)
                    .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(cpA))))
        .isZero();
  }

  @Test
  void confirmCounterpartyRejectsANonexistentContractId() {
    long id = counterpartyWithOneTransaction("CDTR-NOCONTRACT", "No Contract Co");

    assertThatThrownBy(() -> writeTools.confirmCounterparty(id, 999_999L))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(
            db.fetchCount(
                db.selectFrom(COUNTERPARTY_HISTORY)
                    .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id))))
        .isZero();
    Record counterparty =
        db.select(COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(counterparty.get(COUNTERPARTIES.STATUS)).isEqualTo("open");
  }

  @Test
  void dismissCounterpartyRejectsAContractBelongingToAnotherCounterparty() {
    long cpA = counterpartyWithOneTransaction("CDTR-DISM-OWNER-A", "Dismiss Owner A Co");
    long cpB = counterpartyWithOneTransaction("CDTR-DISM-OWNER-B", "Dismiss Owner B Co");
    long contractOfB = seedContract(cpB, "MANDATE-B");

    assertThatThrownBy(
            () -> writeTools.dismissCounterparty(cpA, contractOfB, null, null, "wrong owner", null))
        .isInstanceOf(IllegalArgumentException.class);

    Record contract = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractOfB)).fetchOne();
    assertThat(contract.get(CONTRACTS.STATUS)).isEqualTo("open");
  }

  @Test
  void dismissCounterpartyRejectsANonexistentContractId() {
    long id = counterpartyWithOneTransaction("CDTR-DISM-NOCONTRACT", "Dismiss No Contract Co");

    assertThatThrownBy(
            () -> writeTools.dismissCounterparty(id, 999_999L, null, null, "reason", null))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(
            db.fetchCount(
                db.selectFrom(COUNTERPARTY_HISTORY)
                    .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id))))
        .isZero();
  }

  @Test
  void markRecurringAckIndicatesNoOpWhenGuardedUpsertAffectsZeroRows() {
    long id = counterpartyWithOneTransaction("CDTR-GUARD-ACK", "Guard Ack Co");
    long contractA = seedContract(id, "MANDATE-A");
    seedRecurring(id, contractA, "confirmed", "42.00");

    WriteAck ack =
        writeTools.markRecurring(
            id,
            contractA,
            Cadence.monthly,
            new BigDecimal("1.23"),
            null,
            null,
            TagSource.auto,
            new BigDecimal("0.500"));

    assertThat(ack.message()).containsIgnoringCase("no change");
  }

  @Test
  void confirmContractRecordsTheRealPriorStatusAsHistoryOldValue() {
    long id = counterpartyWithOneTransaction("CDTR-HISTORY", "History Co");
    long contractA = seedContract(id, "MANDATE-A");
    seedRecurring(id, contractA, "auto", "9.99");

    writeTools.confirmCounterparty(id, contractA);

    Record history =
        db.selectFrom(COUNTERPARTY_HISTORY)
            .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id))
            .and(COUNTERPARTY_HISTORY.FIELD.eq("contract:" + contractA))
            .fetchOne();
    assertThat(history).isNotNull();
    assertThat(history.get(COUNTERPARTY_HISTORY.OLD_VALUE)).isEqualTo("open");
    assertThat(history.get(COUNTERPARTY_HISTORY.NEW_VALUE)).isEqualTo("confirmed");
  }
}
