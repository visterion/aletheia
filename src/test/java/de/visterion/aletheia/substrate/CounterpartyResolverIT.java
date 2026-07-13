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
}
