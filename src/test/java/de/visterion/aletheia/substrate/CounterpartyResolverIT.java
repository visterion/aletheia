package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CounterpartyResolverIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    // AbstractPostgresIT only truncates transactions/imports; counterparties is not touched
    // there, so this test cleans it up itself (spec §3, task-5 brief).
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
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

  @Test
  void resolvesOneCounterpartyPerDistinctIdentityWithPriorityAndIsIdempotent() {
    long imp = importId();

    // (a) creditor_id debit, 3 monthly bookings -> also used for evidence-view assertions.
    insertTxn(imp, "hash-a1", LocalDate.of(2026, 1, 1), "10.00", "DBIT", "CDTR-A", null, "Insurer A");
    insertTxn(imp, "hash-a2", LocalDate.of(2026, 2, 1), "10.00", "DBIT", "CDTR-A", null, "Insurer A");
    insertTxn(imp, "hash-a3", LocalDate.of(2026, 3, 3), "10.00", "DBIT", "CDTR-A", null, "Insurer A");

    // (b) no-creditor iban debit.
    insertTxn(imp, "hash-b1", LocalDate.of(2026, 1, 5), "20.00", "DBIT", null, "DE00IBANB", "Landlord B");

    // (c) no-creditor no-iban name-only debit.
    insertTxn(imp, "hash-c1", LocalDate.of(2026, 1, 6), "5.00", "DBIT", null, null, "Streaming C");

    // (d) both creditor_id and iban present -> must resolve via creditor_id, same identity as (a).
    insertTxn(imp, "hash-d1", LocalDate.of(2026, 4, 1), "10.00", "DBIT", "CDTR-A", "DE00IBANOTHER", "Insurer A");

    resolver.run(null);

    var rows =
        db.select(COUNTERPARTIES.IDENTITY_TYPE, COUNTERPARTIES.IDENTITY_VALUE, COUNTERPARTIES.DISPLAY_NAME)
            .from(COUNTERPARTIES)
            .fetch();
    assertThat(rows).hasSize(3); // (a)+(d) collapse into one identity

    assertThat(rows)
        .extracting(r -> r.get(COUNTERPARTIES.IDENTITY_TYPE), r -> r.get(COUNTERPARTIES.IDENTITY_VALUE))
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("creditor_id", "CDTR-A"),
            org.assertj.core.groups.Tuple.tuple("iban", "DE00IBANB"),
            org.assertj.core.groups.Tuple.tuple("name", "STREAMING C"));

    Record creditorRow =
        db.selectFrom(COUNTERPARTIES).where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-A")).fetchOne();
    assertThat(creditorRow.get(COUNTERPARTIES.DISPLAY_NAME)).isEqualTo("Insurer A");

    // Idempotent: running again must not create duplicates.
    resolver.run(null);
    assertThat(db.fetchCount(COUNTERPARTIES)).isEqualTo(3);
  }

  @Test
  void newestUsableNameWinsAndDelayedOlderBookingCannotRollItBack() {
    long initialImport = importId();
    insertTxn(
        initialImport,
        "latest-old",
        LocalDate.of(2026, 1, 1),
        "10.00",
        "DBIT",
        "CDTR-LATEST",
        null,
        "Old Name");
    insertTxn(
        initialImport,
        "latest-current",
        LocalDate.of(2026, 3, 1),
        "10.00",
        "DBIT",
        "CDTR-LATEST",
        null,
        "Current Name");

    resolver.run(null);

    assertThat(
            db.select(COUNTERPARTIES.DISPLAY_NAME)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-LATEST"))
                .fetchOne(COUNTERPARTIES.DISPLAY_NAME))
        .isEqualTo("Current Name");

    long delayedImport = importId();
    insertTxn(
        delayedImport,
        "latest-delayed",
        LocalDate.of(2025, 12, 1),
        "10.00",
        "DBIT",
        "CDTR-LATEST",
        null,
        "Historical Name");

    resolver.run(null);

    assertThat(
            db.select(COUNTERPARTIES.DISPLAY_NAME)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-LATEST"))
                .fetchOne(COUNTERPARTIES.DISPLAY_NAME))
        .isEqualTo("Current Name");
  }

  @Test
  void sameDayNameTieUsesAlphabeticalOrderRegardlessOfInsertionOrder() {
    long imp = importId();
    LocalDate date = LocalDate.of(2026, 4, 1);

    insertTxn(imp, "tie-a-zebra", date, "10.00", "DBIT", "CDTR-TIE-A", null, "Zebra Name");
    insertTxn(imp, "tie-a-alpha", date, "10.00", "DBIT", "CDTR-TIE-A", null, "Alpha Name");
    insertTxn(imp, "tie-b-alpha", date, "10.00", "DBIT", "CDTR-TIE-B", null, "Alpha Name");
    insertTxn(imp, "tie-b-zebra", date, "10.00", "DBIT", "CDTR-TIE-B", null, "Zebra Name");

    resolver.run(null);

    assertThat(
            db.select(COUNTERPARTIES.DISPLAY_NAME)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-TIE-A"))
                .fetchOne(COUNTERPARTIES.DISPLAY_NAME))
        .isEqualTo("Alpha Name");
    assertThat(
            db.select(COUNTERPARTIES.DISPLAY_NAME)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-TIE-B"))
                .fetchOne(COUNTERPARTIES.DISPLAY_NAME))
        .isEqualTo("Alpha Name");
  }

  @Test
  void unusableNamesNeitherEraseDisplayNameNorCreateNameIdentity() {
    long imp = importId();

    insertTxn(
        imp,
        "usable-known",
        LocalDate.of(2026, 1, 1),
        "10.00",
        "DBIT",
        "CDTR-USABLE",
        null,
        "Known Name");
    insertTxn(imp, "usable-null", LocalDate.of(2026, 2, 1), "10.00", "DBIT", "CDTR-USABLE", null, null);
    insertTxn(imp, "usable-empty", LocalDate.of(2026, 2, 2), "10.00", "DBIT", "CDTR-USABLE", null, "");
    insertTxn(imp, "usable-spaces", LocalDate.of(2026, 2, 3), "10.00", "DBIT", "CDTR-USABLE", null, "   ");
    insertTxn(imp, "usable-tabs", LocalDate.of(2026, 2, 4), "10.00", "DBIT", "CDTR-USABLE", null, "\t\t");
    insertTxn(imp, "usable-newlines", LocalDate.of(2026, 2, 5), "10.00", "DBIT", "CDTR-USABLE", null, "\n\r\n");

    insertTxn(imp, "name-empty", LocalDate.of(2026, 3, 1), "5.00", "DBIT", null, null, "");
    insertTxn(imp, "name-spaces", LocalDate.of(2026, 3, 2), "5.00", "DBIT", null, null, "   ");
    insertTxn(imp, "name-tabs", LocalDate.of(2026, 3, 3), "5.00", "DBIT", null, null, "\t");
    insertTxn(imp, "name-newlines", LocalDate.of(2026, 3, 4), "5.00", "DBIT", null, null, "\n");

    resolver.run(null);

    assertThat(
            db.select(COUNTERPARTIES.DISPLAY_NAME)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-USABLE"))
                .fetchOne(COUNTERPARTIES.DISPLAY_NAME))
        .isEqualTo("Known Name");
    assertThat(
            db.fetchExists(
                db.selectOne()
                    .from(COUNTERPARTIES)
                    .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))))
        .isFalse();
  }

  @Test
  void unusableNamesDoNotEraseExistingDisplayName() {
    db.execute(
        """
        INSERT INTO counterparties (identity_type, identity_value, display_name)
        VALUES ('creditor_id', 'CDTR-KNOWN', 'Known Name')
        """);

    long imp = importId();
    insertTxn(imp, "known-null", LocalDate.of(2026, 1, 1), "10.00", "DBIT", "CDTR-KNOWN", null, null);
    insertTxn(imp, "known-empty", LocalDate.of(2026, 2, 1), "10.00", "DBIT", "CDTR-KNOWN", null, "");
    insertTxn(imp, "known-whitespace", LocalDate.of(2026, 3, 1), "10.00", "DBIT", "CDTR-KNOWN", null, " \t\n ");

    resolver.run(null);

    assertThat(
            db.select(COUNTERPARTIES.DISPLAY_NAME)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-KNOWN"))
                .fetchOne(COUNTERPARTIES.DISPLAY_NAME))
        .isEqualTo("Known Name");
  }

  @Test
  void refreshChangesOnlyDisplayNameAndPreservesRelatedState() {
    long counterpartyId =
        db.fetchOne(
                """
                INSERT INTO counterparties
                    (identity_type, identity_value, display_name, reviewed, status, dismissed_reason)
                VALUES ('creditor_id', 'CDTR-STATE', 'Stale Name', true, 'dismissed',
                        'Synthetic test reason')
                RETURNING id
                """)
            .get("id", Long.class);

    db.execute(
        """
        INSERT INTO counterparty_tags
            (counterparty_id, dimension, value, source, confidence)
        VALUES (?, 'nature', 'synthetic-service', 'confirmed', 0.999)
        """,
        counterpartyId);
    long contractId =
        db.fetchOne(
                """
                INSERT INTO contracts
                    (counterparty_id, mandate_id, source, confidence, status,
                     dismissed_reason, hivemem_cell_id, notes)
                VALUES (?, 'MANDATE-STATE', 'confirmed', 0.999, 'dismissed',
                        'Synthetic contract reason', 'synthetic-cell', 'Synthetic notes')
                RETURNING id
                """,
                counterpartyId)
            .get("id", Long.class);
    db.execute(
        """
        INSERT INTO recurring
            (counterparty_id, contract_id, cadence, typical_amount, amount_min, amount_max,
             first_seen, last_seen, occurrence_count, source, confidence)
        VALUES (?, ?, 'monthly', 12.34, 12.34, 12.34,
                DATE '2026-01-01', DATE '2026-02-01', 2, 'confirmed', 0.999)
        """,
        counterpartyId,
        contractId);
    db.execute(
        """
        INSERT INTO counterparty_history
            (counterparty_id, field, old_value, new_value, source, actor)
        VALUES (?, 'status', 'open', 'dismissed', 'confirmed', 'synthetic-test')
        """,
        counterpartyId);

    Record counterpartyBefore =
        db.selectFrom(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(counterpartyId))
            .fetchOne();
    Record tagBefore =
        db.fetchOne("SELECT * FROM counterparty_tags WHERE counterparty_id = ?", counterpartyId);
    Record contractBefore = db.fetchOne("SELECT * FROM contracts WHERE id = ?", contractId);
    Record recurringBefore =
        db.fetchOne(
            "SELECT * FROM recurring WHERE counterparty_id = ? AND contract_id = ?",
            counterpartyId,
            contractId);
    Record historyBefore =
        db.fetchOne("SELECT * FROM counterparty_history WHERE counterparty_id = ?", counterpartyId);

    long imp = importId();
    insertTxn(
        imp,
        "state-latest",
        LocalDate.of(2026, 4, 1),
        "12.34",
        "DBIT",
        "CDTR-STATE",
        null,
        "Latest Bank Name");

    resolver.run(null);

    Record counterpartyAfter =
        db.selectFrom(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(counterpartyId))
            .fetchOne();
    assertThat(counterpartyAfter.get(COUNTERPARTIES.DISPLAY_NAME)).isEqualTo("Latest Bank Name");
    assertThat(counterpartyAfter.get(COUNTERPARTIES.ID))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.ID));
    assertThat(counterpartyAfter.get(COUNTERPARTIES.IDENTITY_TYPE))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.IDENTITY_TYPE));
    assertThat(counterpartyAfter.get(COUNTERPARTIES.IDENTITY_VALUE))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.IDENTITY_VALUE));
    assertThat(counterpartyAfter.get(COUNTERPARTIES.REVIEWED))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.REVIEWED));
    assertThat(counterpartyAfter.get(COUNTERPARTIES.STATUS))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.STATUS));
    assertThat(counterpartyAfter.get(COUNTERPARTIES.DISMISSED_REASON))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.DISMISSED_REASON));
    assertThat(counterpartyAfter.get(COUNTERPARTIES.CREATED_AT))
        .isEqualTo(counterpartyBefore.get(COUNTERPARTIES.CREATED_AT));
    assertThat(db.fetchOne("SELECT * FROM counterparty_tags WHERE counterparty_id = ?", counterpartyId))
        .isEqualTo(tagBefore);
    assertThat(db.fetchOne("SELECT * FROM contracts WHERE id = ?", contractId))
        .isEqualTo(contractBefore);
    assertThat(
            db.fetchOne(
                "SELECT * FROM recurring WHERE counterparty_id = ? AND contract_id = ?",
                counterpartyId,
                contractId))
        .isEqualTo(recurringBefore);
    assertThat(db.fetchOne("SELECT * FROM counterparty_history WHERE counterparty_id = ?", counterpartyId))
        .isEqualTo(historyBefore);
  }

  @Test
  void emptyTransactionsIsANoOp() {
    resolver.run(null);
    assertThat(db.fetchCount(COUNTERPARTIES)).isZero();
  }

  @Test
  void evidenceViewAggregatesMatchHandComputedValuesForCreditorSeries() {
    long imp = importId();
    // Creditor series: 4 debits at gaps 31, 30, 31 days (Jan1, Feb1, Mar4, Apr3).
    insertTxn(imp, "hash-e1", LocalDate.of(2026, 1, 1), "10.00", "DBIT", "CDTR-E", null, "Evidence Co");
    insertTxn(imp, "hash-e2", LocalDate.of(2026, 2, 1), "10.00", "DBIT", "CDTR-E", null, "Evidence Co");
    insertTxn(imp, "hash-e3", LocalDate.of(2026, 3, 4), "12.00", "DBIT", "CDTR-E", null, "Evidence Co");
    insertTxn(imp, "hash-e4", LocalDate.of(2026, 4, 3), "10.00", "DBIT", "CDTR-E", null, "Evidence Co");

    resolver.run(null);

    var view =
        db.fetch(
                "SELECT v.* FROM v_counterparty_evidence v "
                    + "JOIN counterparties c ON c.id = v.counterparty_id "
                    + "WHERE c.identity_value = 'CDTR-E'")
            .get(0);

    assertThat(view.get("txn_count", Integer.class)).isEqualTo(4);
    assertThat(view.get("first_seen", LocalDate.class)).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(view.get("last_seen", LocalDate.class)).isEqualTo(LocalDate.of(2026, 4, 3));
    assertThat(view.get("span_days", Integer.class)).isEqualTo(92); // Jan1 -> Apr3
    assertThat(view.get("total_amount", BigDecimal.class)).isEqualByComparingTo("42.00");
    // gaps: 31, 31, 30 -> median 31
    assertThat(view.get("median_gap_days", Double.class)).isEqualTo(31.0);
    assertThat(view.get("direction", String.class)).isEqualTo("DBIT");
  }

  @Test
  void ignoresSplitChildrenAndDoesNotCreateCounterpartiesFromThem() {
    // TP2 requirement: resolvers must not process child rows (split_parent set, import_id null).
    // "Bargeld" must only be created by split tool (with nature tag), never auto here.
    long imp = importId();

    // Parent row (raw) -> should create one CP.
    insertTxn(imp, "parent-hash-cp", LocalDate.of(2026, 1, 1), "10.00", "DBIT", "CDTR-P", null, "Parent Co");

    // Child row simulating split part (Bargeld) -> must be ignored.
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-bargeld-ignored")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("5.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Bargeld")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, "parent-hash-cp")
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();

    resolver.run(null);

    var cps =
        db.select(COUNTERPARTIES.IDENTITY_TYPE, COUNTERPARTIES.IDENTITY_VALUE, COUNTERPARTIES.DISPLAY_NAME)
            .from(COUNTERPARTIES)
            .fetch();
    assertThat(cps).hasSize(1);
    assertThat(cps.get(0).get(COUNTERPARTIES.IDENTITY_VALUE)).isEqualTo("CDTR-P");
    // No name-based "BARGELD" was created from the child.
    assertThat(
            db.fetchExists(
                db.selectOne()
                    .from(COUNTERPARTIES)
                    .where(COUNTERPARTIES.IDENTITY_VALUE.eq("BARGELD"))))
        .isFalse();
  }
}
