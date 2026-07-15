package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
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

class WriteToolsIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired WriteTools writeTools;

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

  private org.jooq.Result<?> historyFor(long counterpartyId) {
    return db.selectFrom(COUNTERPARTY_HISTORY)
        .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(counterpartyId))
        .fetch();
  }

  @Test
  void classifyCounterpartyUpsertsTagsAndLogsHistory() {
    long id = counterpartyWithOneTransaction("CDTR-CLASSIFY", "Classify Co");

    writeTools.classifyCounterparty(
        List.of(id),
        null,
        List.of(new TagInput("domain", "telecom")),
        TagSource.auto,
        new BigDecimal("0.900"),
        false);

    var tags =
        db.selectFrom(COUNTERPARTY_TAGS).where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(tags).hasSize(1);
    assertThat(tags.get(0).get(COUNTERPARTY_TAGS.VALUE)).isEqualTo("telecom");
    assertThat(tags.get(0).get(COUNTERPARTY_TAGS.SOURCE)).isEqualTo("auto");

    assertThat(historyFor(id)).hasSize(1);
    assertThat(historyFor(id).get(0).get(COUNTERPARTY_HISTORY.FIELD)).isEqualTo("tag:domain");
    assertThat(historyFor(id).get(0).get(COUNTERPARTY_HISTORY.NEW_VALUE)).isEqualTo("telecom");
  }

  @Test
  void classifyCounterpartyWithSourceAutoNeverSetsReviewedOrStatus() {
    long id = counterpartyWithOneTransaction("CDTR-AUTO", "Auto Co");

    writeTools.classifyCounterparty(
        List.of(id), null, List.of(new TagInput("nature", "subscription")), TagSource.auto, null, false);

    Record row =
        db.select(COUNTERPARTIES.REVIEWED, COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(row.get(COUNTERPARTIES.REVIEWED)).isFalse();
    assertThat(row.get(COUNTERPARTIES.STATUS)).isEqualTo("open");
  }

  @Test
  void classifyCounterpartyReplacesTagsForTheSameDimension() {
    long id = counterpartyWithOneTransaction("CDTR-REPLACE", "Replace Co");
    writeTools.classifyCounterparty(
        List.of(id), null, List.of(new TagInput("domain", "old-value")), TagSource.auto, null, false);

    writeTools.classifyCounterparty(
        List.of(id), null, List.of(new TagInput("domain", "new-value")), TagSource.confirmed, null, false);

    var tags =
        db.selectFrom(COUNTERPARTY_TAGS).where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(tags).hasSize(1);
    assertThat(tags.get(0).get(COUNTERPARTY_TAGS.VALUE)).isEqualTo("new-value");
  }

  @Test
  void markRecurringUpsertsTheRecurringRow() {
    long id = counterpartyWithOneTransaction("CDTR-RECUR", "Recur Co");

    writeTools.markRecurring(
        id, null, Cadence.monthly, new BigDecimal("9.99"), null, null, TagSource.auto, new BigDecimal("0.800"));

    Record row = db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(row.get(RECURRING.CADENCE)).isEqualTo("monthly");
    assertThat(row.get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo("9.99");

    // Second call upserts (UNIQUE(counterparty_id, contract_id)) rather than duplicating.
    writeTools.markRecurring(
        id, null, Cadence.yearly, new BigDecimal("99.00"), null, null, TagSource.confirmed, new BigDecimal("1.000"));

    var rows = db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get(RECURRING.CADENCE)).isEqualTo("yearly");
    assertThat(rows.get(0).get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo("99.00");

    assertThat(historyFor(id)).hasSize(2);
  }

  @Test
  void markRecurringWithSourceAutoNeverSetsReviewedOrStatus() {
    long id = counterpartyWithOneTransaction("CDTR-RECUR-AUTO", "Recur Auto Co");

    writeTools.markRecurring(
        id, null, Cadence.monthly, new BigDecimal("5.00"), null, null, TagSource.auto, null);

    Record row =
        db.select(COUNTERPARTIES.REVIEWED, COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(row.get(COUNTERPARTIES.REVIEWED)).isFalse();
    assertThat(row.get(COUNTERPARTIES.STATUS)).isEqualTo("open");
  }

  @Test
  void confirmWithoutContractIdOrRecurringFlipsAutoTagsAndSetsReviewedAndStatus() {
    // No recurring row at all -> legacy tag-confirm path (no mandate-less series to
    // materialize into a contract).
    long id = counterpartyWithOneTransaction("CDTR-CONFIRM", "Confirm Co");
    writeTools.classifyCounterparty(
        List.of(id), null, List.of(new TagInput("domain", "telecom")), TagSource.auto, null, false);

    writeTools.confirmCounterparty(id, null);

    Record counterparty =
        db.select(COUNTERPARTIES.REVIEWED, COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(counterparty.get(COUNTERPARTIES.REVIEWED)).isTrue();
    assertThat(counterparty.get(COUNTERPARTIES.STATUS)).isEqualTo("confirmed");

    Record tag = db.selectFrom(COUNTERPARTY_TAGS).where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(tag.get(COUNTERPARTY_TAGS.SOURCE)).isEqualTo("confirmed");
  }

  @Test
  void confirmDrainsTheReviewQueue() {
    long id = counterpartyWithOneTransaction("CDTR-DRAIN", "Drain Co");
    assertThat(
            db.select(COUNTERPARTIES.STATUS)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.ID.eq(id))
                .fetchOne(COUNTERPARTIES.STATUS))
        .isEqualTo("open");

    writeTools.confirmCounterparty(id, null);

    assertThat(
            db.fetchCount(
                COUNTERPARTIES, COUNTERPARTIES.ID.eq(id).and(COUNTERPARTIES.STATUS.eq("open"))))
        .isZero();
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

  @Test
  void linkContractUpdatesTheTargetedContractsRow() {
    long id = counterpartyWithOneTransaction("CDTR-CONTRACT", "Contract Co");
    long contractId = seedContract(id, "MANDATE-1");

    writeTools.linkContract(contractId, "hivemem-cell-1", "first link");

    Record contract = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractId)).fetchOne();
    assertThat(contract.get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("hivemem-cell-1");

    writeTools.linkContract(contractId, "hivemem-cell-2", "updated link");

    var contracts = db.selectFrom(CONTRACTS).where(CONTRACTS.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(contracts).hasSize(1);
    assertThat(contracts.get(0).get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("hivemem-cell-2");

    assertThat(historyFor(id)).hasSize(2);
  }

  @Test
  void linkContractPreservesExistingNotesWhenNotesParamIsOmittedOnRelink() {
    long id = counterpartyWithOneTransaction("CDTR-RELINK", "Relink Co");
    long contractId = seedContract(id, "MANDATE-1");

    writeTools.linkContract(contractId, "hivemem-cell-1", "keep me");
    writeTools.linkContract(contractId, "hivemem-cell-2", null);

    Record contract = db.selectFrom(CONTRACTS).where(CONTRACTS.ID.eq(contractId)).fetchOne();
    assertThat(contract.get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("hivemem-cell-2");
    assertThat(contract.get(CONTRACTS.NOTES)).isEqualTo("keep me");
  }

  @Test
  void linkContractRejectsAnUnknownContractId() {
    assertThatThrownBy(() -> writeTools.linkContract(999_999L, "cell", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dismissWithoutContractIdOrRecurringSetsStatusAndReason() {
    long id = counterpartyWithOneTransaction("CDTR-DISMISS", "Dismiss Co");

    writeTools.dismissCounterparty(id, "one-off refund, not recurring", null);

    Record row =
        db.select(COUNTERPARTIES.STATUS, COUNTERPARTIES.DISMISSED_REASON)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(row.get(COUNTERPARTIES.STATUS)).isEqualTo("dismissed");
    assertThat(row.get(COUNTERPARTIES.DISMISSED_REASON)).isEqualTo("one-off refund, not recurring");

    assertThat(historyFor(id)).hasSize(1);
    assertThat(historyFor(id).get(0).get(COUNTERPARTY_HISTORY.NEW_VALUE)).isEqualTo("dismissed");
  }

  @Test
  void writeToolsRejectAnUnknownCounterpartyId() {
    assertThatThrownBy(() -> writeTools.dismissCounterparty(999_999L, "no such counterparty", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- split_transaction tests (TDD for Phase 2) ---

  private long seedParentTx(String hash, String creditor, String name, String amount, String mndt) {
    long imp =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "split-test.json")
            .set(IMPORTS.FILE_SHA256, "sha-split-" + UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now().minusDays(1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditor)
        .set(TRANSACTIONS.MANDATE_ID, mndt)
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
    return imp;
  }

  @Test
  void splitTransactionHappyPathCreatesChildrenWithCorrectRefsAndAttribution() {
    String pHash = "parent-hash-001";
    seedParentTx(pHash, "CRED-001", "REWE Markt", "79.14", "MAND-XYZ");
    resolver.run(null);
    long merchantId =
        db.select(COUNTERPARTIES.ID)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CRED-001"))
            .fetchOne(COUNTERPARTIES.ID);

    // split 50 purchase + 29.14 bargeld
    var ack =
        writeTools.splitTransaction(
            new TxReference(pHash, 0),
            List.of(
                new Allocation(merchantId, null, null, new BigDecimal("50.00"), "REWE Einkauf reduziert"),
                new Allocation(null, "Bargeld", null, new BigDecimal("29.14"), "Bargeld abgehoben")),
            null);

    assertThat(ack.unsplitPerformed()).isFalse();
    assertThat(ack.allocationsCreated()).isEqualTo(2);
    // one new CP (Bargeld)
    assertThat(ack.createdCounterpartyIds()).hasSize(1);

    // verify children in DB
    var children =
        db.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(pHash))
            .orderBy(TRANSACTIONS.AMOUNT.desc())
            .fetch();
    assertThat(children).hasSize(2);
    assertThat(children.get(0).get(TRANSACTIONS.AMOUNT)).isEqualByComparingTo("50.00");
    assertThat(children.get(0).get(TRANSACTIONS.REMITTANCE_INFO)).isEqualTo("REWE Einkauf reduziert");
    assertThat(children.get(0).get(TRANSACTIONS.CREDITOR_ID)).isEqualTo("CRED-001");
    assertThat(children.get(0).get(TRANSACTIONS.MANDATE_ID)).isEqualTo("MAND-XYZ");
    assertThat(children.get(0).get(TRANSACTIONS.IMPORT_ID)).isNull();
    assertThat(children.get(0).get(TRANSACTIONS.OCCURRENCE_INDEX)).isEqualTo(0);

    assertThat(children.get(1).get(TRANSACTIONS.AMOUNT)).isEqualByComparingTo("29.14");
    assertThat(children.get(1).get(TRANSACTIONS.COUNTERPARTY_NAME)).isEqualTo("Bargeld");
    assertThat(children.get(1).get(TRANSACTIONS.CREDITOR_ID)).isNull();
    assertThat(children.get(1).get(TRANSACTIONS.MANDATE_ID)).isNull();
    assertThat(children.get(1).get(TRANSACTIONS.IMPORT_ID)).isNull();

    // Bargeld CP has the nature tag
    long bargeldId = ack.createdCounterpartyIds().get(0);
    var nature =
        db.select(COUNTERPARTY_TAGS.VALUE)
            .from(COUNTERPARTY_TAGS)
            .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(bargeldId))
            .and(COUNTERPARTY_TAGS.DIMENSION.eq("nature"))
            .fetchOne(COUNTERPARTY_TAGS.VALUE);
    assertThat(nature).isEqualTo("umbuchung");

    // parent row untouched
    var parentStill =
        db.fetchExists(
            db.selectOne().from(TRANSACTIONS).where(TRANSACTIONS.CONTENT_HASH.eq(pHash)));
    assertThat(parentStill).isTrue();
  }

  @Test
  void splitTransactionUnsplitDeletesChildrenAndIsIdempotent() {
    String pHash = "parent-hash-002";
    seedParentTx(pHash, "CRED-002", "TestShop", "10.00", null);

    writeTools.splitTransaction(
        new TxReference(pHash, 0),
        List.of(
            new Allocation(null, "TestShop", null, new BigDecimal("6.00"), "teil1"),
            new Allocation(null, "Bargeld", null, new BigDecimal("4.00"), "teil2")),
        false);

    assertThat(
            db.fetchCount(
                TRANSACTIONS, TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(pHash)))
        .isEqualTo(2);

    // unsplit
    var ack = writeTools.splitTransaction(new TxReference(pHash, 0), null, true);
    assertThat(ack.unsplitPerformed()).isTrue();
    assertThat(
            db.fetchCount(
                TRANSACTIONS, TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(pHash)))
        .isZero();

    // second unsplit no-op side-effect free
    writeTools.splitTransaction(new TxReference(pHash, 0), List.of(), true);
    assertThat(
            db.fetchCount(
                TRANSACTIONS, TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(pHash)))
        .isZero();
  }

  @Test
  void splitTransactionRejectsOnSumMismatch() {
    String pHash = "parent-hash-003";
    seedParentTx(pHash, "CRED-003", "SumTest", "100.00", null);

    assertThatThrownBy(
            () ->
                writeTools.splitTransaction(
                    new TxReference(pHash, 0),
                    List.of(
                        new Allocation(null, "Foo", null, new BigDecimal("40.00"), "a"),
                        new Allocation(null, "Bar", null, new BigDecimal("50.00"), "b")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sum of allocations")
        .hasMessageContaining("100.00");
  }

  @Test
  void splitTransactionIsReplaceAndIdempotentOnIdenticalCall() {
    String pHash = "parent-hash-004";
    seedParentTx(pHash, null, "IdemShop", "20.00", null);

    var first =
        writeTools.splitTransaction(
            new TxReference(pHash, 0),
            List.of(new Allocation(null, "IdemShop", null, new BigDecimal("20.00"), "full")),
            null);
    assertThat(first.allocationsCreated()).isEqualTo(1);
    long count1 =
        db.fetchCount(TRANSACTIONS, TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(pHash));
    assertThat(count1).isEqualTo(1);

    // identical call again
    var second =
        writeTools.splitTransaction(
            new TxReference(pHash, 0),
            List.of(new Allocation(null, "IdemShop", null, new BigDecimal("20.00"), "full")),
            null);
    assertThat(second.allocationsCreated()).isEqualTo(1);
    long count2 =
        db.fetchCount(TRANSACTIONS, TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(pHash));
    assertThat(count2).isEqualTo(1); // still 1, replaced not duplicated
  }

  @Test
  void splitTransactionRejectsWhenTargetIsAlreadyAChild() {
    String rootHash = "parent-root-nested-001";
    seedParentTx(rootHash, "CRED-N", "NestShop", "30.00", null);

    writeTools.splitTransaction(
        new TxReference(rootHash, 0),
        List.of(
            new Allocation(null, "NestShop", null, new BigDecimal("20.00"), "purchase"),
            new Allocation(null, "Bargeld", null, new BigDecimal("10.00"), "cash")),
        null);

    String childHash =
        db.select(TRANSACTIONS.CONTENT_HASH)
            .from(TRANSACTIONS)
            .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(rootHash))
            .and(TRANSACTIONS.COUNTERPARTY_NAME.eq("NestShop"))
            .fetchOne(TRANSACTIONS.CONTENT_HASH);

    assertThatThrownBy(
            () ->
                writeTools.splitTransaction(
                    new TxReference(childHash, 0),
                    List.of(
                        new Allocation(null, "NestShop", null, new BigDecimal("12.00"), "a"),
                        new Allocation(null, "Bargeld", null, new BigDecimal("8.00"), "b")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("child");

    // No grandchildren
    assertThat(
            db.fetchCount(
                TRANSACTIONS,
                TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(childHash)))
        .isZero();
  }
}
