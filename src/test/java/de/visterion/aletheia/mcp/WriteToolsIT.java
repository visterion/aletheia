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

import de.visterion.aletheia.ingest.AbstractPostgresIT;
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
        id, List.of(new TagInput("domain", "telecom")), "auto", new BigDecimal("0.900"));

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
        id, List.of(new TagInput("nature", "subscription")), "auto", null);

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
    writeTools.classifyCounterparty(id, List.of(new TagInput("domain", "old-value")), "auto", null);

    writeTools.classifyCounterparty(id, List.of(new TagInput("domain", "new-value")), "confirmed", null);

    var tags =
        db.selectFrom(COUNTERPARTY_TAGS).where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(tags).hasSize(1);
    assertThat(tags.get(0).get(COUNTERPARTY_TAGS.VALUE)).isEqualTo("new-value");
  }

  @Test
  void markRecurringUpsertsTheRecurringRow() {
    long id = counterpartyWithOneTransaction("CDTR-RECUR", "Recur Co");

    writeTools.markRecurring(
        id, "monthly", new BigDecimal("9.99"), null, null, "auto", new BigDecimal("0.800"));

    Record row = db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(row.get(RECURRING.CADENCE)).isEqualTo("monthly");
    assertThat(row.get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo("9.99");

    // Second call upserts (UNIQUE(counterparty_id)) rather than duplicating.
    writeTools.markRecurring(
        id, "yearly", new BigDecimal("99.00"), null, null, "confirmed", new BigDecimal("1.000"));

    var rows = db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get(RECURRING.CADENCE)).isEqualTo("yearly");
    assertThat(rows.get(0).get(RECURRING.TYPICAL_AMOUNT)).isEqualByComparingTo("99.00");

    assertThat(historyFor(id)).hasSize(2);
  }

  @Test
  void markRecurringWithSourceAutoNeverSetsReviewedOrStatus() {
    long id = counterpartyWithOneTransaction("CDTR-RECUR-AUTO", "Recur Auto Co");

    writeTools.markRecurring(id, "monthly", new BigDecimal("5.00"), null, null, "auto", null);

    Record row =
        db.select(COUNTERPARTIES.REVIEWED, COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(row.get(COUNTERPARTIES.REVIEWED)).isFalse();
    assertThat(row.get(COUNTERPARTIES.STATUS)).isEqualTo("open");
  }

  @Test
  void confirmFlipsAutoTagsAndRecurringToConfirmedAndSetsReviewedAndStatus() {
    long id = counterpartyWithOneTransaction("CDTR-CONFIRM", "Confirm Co");
    writeTools.classifyCounterparty(id, List.of(new TagInput("domain", "telecom")), "auto", null);
    writeTools.markRecurring(id, "monthly", new BigDecimal("10.00"), null, null, "auto", null);

    writeTools.confirm(id);

    Record counterparty =
        db.select(COUNTERPARTIES.REVIEWED, COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(id))
            .fetchOne();
    assertThat(counterparty.get(COUNTERPARTIES.REVIEWED)).isTrue();
    assertThat(counterparty.get(COUNTERPARTIES.STATUS)).isEqualTo("confirmed");

    Record tag = db.selectFrom(COUNTERPARTY_TAGS).where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(tag.get(COUNTERPARTY_TAGS.SOURCE)).isEqualTo("confirmed");

    Record recurring = db.selectFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(recurring.get(RECURRING.SOURCE)).isEqualTo("confirmed");
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

    writeTools.confirm(id);

    assertThat(
            db.fetchCount(
                COUNTERPARTIES, COUNTERPARTIES.ID.eq(id).and(COUNTERPARTIES.STATUS.eq("open"))))
        .isZero();
  }

  @Test
  void linkContractInsertsThenUpdatesTheContractsRow() {
    long id = counterpartyWithOneTransaction("CDTR-CONTRACT", "Contract Co");

    writeTools.linkContract(id, "hivemem-cell-1", "first link");

    Record contract = db.selectFrom(CONTRACTS).where(CONTRACTS.COUNTERPARTY_ID.eq(id)).fetchOne();
    assertThat(contract.get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("hivemem-cell-1");

    writeTools.linkContract(id, "hivemem-cell-2", "updated link");

    var contracts = db.selectFrom(CONTRACTS).where(CONTRACTS.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(contracts).hasSize(1);
    assertThat(contracts.get(0).get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("hivemem-cell-2");

    assertThat(historyFor(id)).hasSize(2);
  }

  @Test
  void linkContractCalledTwiceProducesExactlyOneContractsRow() {
    long id = counterpartyWithOneTransaction("CDTR-CONTRACT-DUP", "Contract Dup Co");

    writeTools.linkContract(id, "hivemem-cell-a", "first link");
    writeTools.linkContract(id, "hivemem-cell-b", "second link");

    var contracts = db.selectFrom(CONTRACTS).where(CONTRACTS.COUNTERPARTY_ID.eq(id)).fetch();
    assertThat(contracts).hasSize(1);
    assertThat(contracts.get(0).get(CONTRACTS.HIVEMEM_CELL_ID)).isEqualTo("hivemem-cell-b");
  }

  @Test
  void dismissSetsStatusAndReason() {
    long id = counterpartyWithOneTransaction("CDTR-DISMISS", "Dismiss Co");

    writeTools.dismiss(id, "one-off refund, not recurring");

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
    assertThatThrownBy(() -> writeTools.dismiss(999_999L, "no such counterparty"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
