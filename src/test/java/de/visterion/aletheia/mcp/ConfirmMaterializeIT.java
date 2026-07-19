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
import de.visterion.aletheia.mcp.handlers.write.ConfirmCounterpartyToolHandler;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 6 (Spec B, P4 confirm-materialize): {@code confirm_counterparty} with an optional {@code
 * cadence} fuses series-creation + contract materialize + confirm into a single call, single
 * counterparty only. Order matters: materialize the mandate-less contract first (repoints any
 * existing {@code (cp,NULL)} recurring row), then mark the series recurring keyed on {@code
 * (cp, contractId)} -- so no orphan row and no unique-key collision. A dismissed mandate-less
 * contract is never silently resurrected by this path.
 */
class ConfirmMaterializeIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ContractResolver contractResolver;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;
  @Autowired ConfirmCounterpartyToolHandler confirmHandler;

  private final JsonMapper mapper = JsonMapper.builder().build();

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
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
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

  private Record mandatelessContractRow(long counterpartyId) {
    return db.selectFrom(CONTRACTS)
        .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
        .and(CONTRACTS.MANDATE_ID.isNull())
        .fetchOne();
  }

  private List<? extends Record> recurringRowsFor(long counterpartyId) {
    return db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(counterpartyId)).fetch();
  }

  // --- no prior recurring: create ---

  @Test
  void noPriorRecurringCreatesSeriesAndMaterializesConfirmedContract() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-NEW", "Fuse New Co");

    BatchWriteAck ack =
        writeTools.confirmCounterparty(
            id, null, null, null, null, Cadence.monthly, new BigDecimal("9.99"), null, null);

    assertThat(ack.affectedCount()).isEqualTo(1);

    Record contract = mandatelessContractRow(id);
    assertThat(contract).isNotNull();
    assertThat(contract.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
    assertThat(contract.get(CONTRACTS.SOURCE)).isEqualTo("confirmed");

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    assertThat(recurring.get(0).get(RECURRING.CONTRACT_ID)).isEqualTo(contract.get(CONTRACTS.ID));
    assertThat(recurring.get(0).get(RECURRING.SOURCE)).isEqualTo("confirmed");
    assertThat(recurring.get(0).get(RECURRING.CADENCE)).isEqualTo("monthly");
    assertThat(recurring.get(0).get(RECURRING.TYPICAL_AMOUNT))
        .isEqualByComparingTo(new BigDecimal("9.99"));

    Record cp = db.selectFrom(COUNTERPARTIES).where(COUNTERPARTIES.ID.eq(id)).fetchOne();
    assertThat(cp.get(COUNTERPARTIES.REVIEWED)).isTrue();
    assertThat(cp.get(COUNTERPARTIES.STATUS)).isEqualTo("confirmed");

    ObligationsRegister register = readTools.obligationsRegister();
    assertThat(register.rows())
        .anyMatch(
            r ->
                Long.valueOf(contract.get(CONTRACTS.ID, Long.class)).equals(r.contractId())
                    && r.annualCost().compareTo(BigDecimal.ZERO) > 0);
  }

  // --- existing auto mandate-less recurring: overwritten, one row ---

  @Test
  void existingAutoRecurringIsOverwrittenIntoOneRowNoOrphan() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-AUTO", "Fuse Auto Co");
    seedRecurring(id, null, "auto", "5.00");

    writeTools.confirmCounterparty(
        id, null, null, null, null, Cadence.quarterly, new BigDecimal("30.00"), null, null);

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1); // no orphan (cp,NULL) row
    Record row = recurring.get(0);
    assertThat(row.get(RECURRING.CONTRACT_ID)).isNotNull();
    assertThat(row.get(RECURRING.SOURCE)).isEqualTo("confirmed");
    assertThat(row.get(RECURRING.CADENCE)).isEqualTo("quarterly");
    assertThat(row.get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo(new BigDecimal("30.00"));
  }

  // --- re-adjust an already-confirmed series: actually changes ---

  @Test
  void reAdjustingAnAlreadyConfirmedSeriesActuallyChangesItNotASilentNoOp() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-READJUST", "Fuse Readjust Co");

    writeTools.confirmCounterparty(
        id, null, null, null, null, Cadence.monthly, new BigDecimal("9.99"), null, null);

    // re-adjust the now-confirmed series via a second fused confirm call
    writeTools.confirmCounterparty(
        id, null, null, null, null, Cadence.yearly, new BigDecimal("120.00"), null, null);

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    Record row = recurring.get(0);
    assertThat(row.get(RECURRING.CADENCE)).isEqualTo("yearly");
    assertThat(row.get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo(new BigDecimal("120.00"));
    assertThat(row.get(RECURRING.SOURCE)).isEqualTo("confirmed");
  }

  // --- re-ingest after materialize doesn't stomp the confirmed series ---

  @Test
  void reIngestAfterMaterializeDoesNotStompTheConfirmedSeries() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-REINGEST", "Fuse Reingest Co");

    writeTools.confirmCounterparty(
        id, null, null, null, null, Cadence.monthly, new BigDecimal("9.99"), null, null);

    resolver.resolve();
    contractResolver.resolve();

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    Record row = recurring.get(0);
    assertThat(row.get(RECURRING.SOURCE)).isEqualTo("confirmed");
    assertThat(row.get(RECURRING.CADENCE)).isEqualTo("monthly");
    assertThat(row.get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo(new BigDecimal("9.99"));

    Record contract = mandatelessContractRow(id);
    assertThat(contract.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
  }

  // --- validation rejects ---

  @Test
  void amountMinGreaterThanAmountMaxIsRejected() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-MINMAX", "Fuse MinMax Co");

    assertThatThrownBy(
            () ->
                writeTools.confirmCounterparty(
                    id,
                    null,
                    null,
                    null,
                    null,
                    Cadence.monthly,
                    new BigDecimal("9.99"),
                    new BigDecimal("50.00"),
                    new BigDecimal("10.00")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(mandatelessContractRow(id)).isNull();
    assertThat(recurringRowsFor(id)).isEmpty();
  }

  @Test
  void invalidCadenceIsRejectedAtTheHandlerLayer() {
    // Cadence is a typed Java enum in WriteTools, so an invalid value can only be exercised
    // where raw wire JSON is parsed -- the ConfirmCounterpartyToolHandler / ArgumentParser.
    long id = counterpartyWithOneTransaction("CDTR-FUSE-BADCADENCE", "Fuse Bad Cadence Co");
    var arguments =
        mapper.readTree(
            "{\"counterpartyId\":"
                + id
                + ",\"cadence\":\"NOPE\",\"typicalAmount\":9.99}");

    assertThatThrownBy(() -> confirmHandler.call(null, arguments))
        .isInstanceOf(McpArgumentException.class);

    assertThat(mandatelessContractRow(id)).isNull();
  }

  @Test
  void batchWithCadenceIsRejected() {
    long a = counterpartyWithOneTransaction("CDTR-FUSE-BATCH-A", "Fuse Batch A Co");
    long b = counterpartyWithOneTransaction("CDTR-FUSE-BATCH-B", "Fuse Batch B Co");

    assertThatThrownBy(
            () ->
                writeTools.confirmCounterparty(
                    null,
                    null,
                    List.of(a, b),
                    null,
                    null,
                    Cadence.monthly,
                    new BigDecimal("9.99"),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(mandatelessContractRow(a)).isNull();
    assertThat(mandatelessContractRow(b)).isNull();
  }

  @Test
  void whereSelectorWithCadenceIsRejected() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-WHERE", "Fuse Where Co");
    CounterpartySelector where =
        new CounterpartySelector(
            null, "Fuse Where", null, null, null, null, null, null, null, null, null, null, null,
            null, null);

    assertThatThrownBy(
            () ->
                writeTools.confirmCounterparty(
                    null,
                    null,
                    null,
                    where,
                    null,
                    Cadence.monthly,
                    new BigDecimal("9.99"),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(mandatelessContractRow(id)).isNull();
  }

  // --- no-cadence regression: identical to current behavior ---

  @Test
  void noCadenceConfirmBehavesLikeBeforeTheFusedPath() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-REGRESSION", "Fuse Regression Co");
    seedRecurring(id, null, "auto", "5.00");

    writeTools.confirmCounterparty(id, null, null, null, null, null, null, null, null);

    Record contract = mandatelessContractRow(id);
    assertThat(contract).isNotNull();
    assertThat(contract.get(CONTRACTS.STATUS)).isEqualTo("confirmed");

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    assertThat(recurring.get(0).get(RECURRING.TYPICAL_AMOUNT))
        .isEqualByComparingTo(new BigDecimal("5.00")); // untouched by the legacy path
  }

  // --- ended mandate-less contract: reopened, series attached ---

  @Test
  void endedMandatelessContractIsReopenedWithSeriesAttachedOneRow() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-ENDED", "Fuse Ended Co");
    long contractId = seedContract(id, null, "confirmed");
    seedRecurring(id, contractId, "confirmed", "9.99");
    writeTools.endContract(contractId, null, "cancelled for now");
    assertThat(contractRow(contractId).get(CONTRACTS.STATUS)).isEqualTo("ended");

    writeTools.confirmCounterparty(
        id, null, null, null, null, Cadence.monthly, new BigDecimal("12.00"), null, null);

    Record reopened = contractRow(contractId);
    assertThat(reopened.get(CONTRACTS.STATUS)).isEqualTo("confirmed");
    assertThat(reopened.get(CONTRACTS.END_DATE)).isNull();

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    assertThat(recurring.get(0).get(RECURRING.CONTRACT_ID)).isEqualTo(contractId);
    assertThat(recurring.get(0).get(RECURRING.TYPICAL_AMOUNT))
        .isEqualByComparingTo(new BigDecimal("12.00"));
  }

  // --- dismissed mandate-less contract: rejected, no resurrection ---

  @Test
  void dismissedMandatelessContractRejectsTheFusedConfirmAndStaysDismissed() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-DISMISSED", "Fuse Dismissed Co");
    seedRecurring(id, null, "auto", "5.00");
    writeTools.dismissCounterparty(id, null, null, null, "not wanted", null);
    long contractId = mandatelessContractRow(id).get(CONTRACTS.ID, Long.class);
    assertThat(contractRow(contractId).get(CONTRACTS.STATUS)).isEqualTo("dismissed");

    assertThatThrownBy(
            () ->
                writeTools.confirmCounterparty(
                    id,
                    null,
                    null,
                    null,
                    null,
                    Cadence.monthly,
                    new BigDecimal("9.99"),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    Record row = contractRow(contractId);
    assertThat(row.get(CONTRACTS.STATUS)).isEqualTo("dismissed");

    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    assertThat(recurring.get(0).get(RECURRING.SOURCE)).isEqualTo("auto"); // untouched
  }

  // --- I1: contractId + cadence is rejected (money-path double-count) ---

  @Test
  void contractIdCombinedWithCadenceIsRejectedAndMakesNoChange() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-CONTRACTID", "Fuse ContractId Co");
    long contractId = seedContract(id, "MANDATE-EXISTING", "confirmed");
    seedRecurring(id, contractId, "confirmed", "9.99");

    assertThatThrownBy(
            () ->
                writeTools.confirmCounterparty(
                    id,
                    contractId,
                    null,
                    null,
                    null,
                    Cadence.monthly,
                    new BigDecimal("45.00"),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    // no second (mandate-less) contract was created
    assertThat(mandatelessContractRow(id)).isNull();
    // the existing mandate contract is unchanged
    assertThat(contractRow(contractId).get(CONTRACTS.STATUS)).isEqualTo("confirmed");
    // no new recurring row was written
    List<? extends Record> recurring = recurringRowsFor(id);
    assertThat(recurring).hasSize(1);
    assertThat(recurring.get(0).get(RECURRING.CONTRACT_ID)).isEqualTo(contractId);
    assertThat(recurring.get(0).get(RECURRING.TYPICAL_AMOUNT))
        .isEqualByComparingTo(new BigDecimal("9.99")); // untouched
  }

  // --- M1: fused confirm writes a counterparty_history status row (audit parity) ---

  @Test
  void fusedConfirmWritesCounterpartyStatusHistoryRow() {
    long id = counterpartyWithOneTransaction("CDTR-FUSE-HISTORY", "Fuse History Co");

    writeTools.confirmCounterparty(
        id, null, null, null, null, Cadence.monthly, new BigDecimal("9.99"), null, null);

    boolean hasStatusHistory =
        db.fetchExists(
            db.selectOne()
                .from(COUNTERPARTY_HISTORY)
                .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id))
                .and(COUNTERPARTY_HISTORY.FIELD.eq("status"))
                .and(COUNTERPARTY_HISTORY.NEW_VALUE.eq("confirmed")));
    assertThat(hasStatusHistory).isTrue();
  }
}
