package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.mcp.ReadTools;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PayPalStaleContractIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;
  @Autowired ReadTools readTools;

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
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void attributed(long imp, String hash, LocalDate date, String merchant) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, date)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-PP-CREDITOR")
        .set(TRANSACTIONS.MANDATE_ID, "PP-SHARED-MANDATE")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "PayPal Europe S.a.r.l.")
        .set(TRANSACTIONS.ATTRIBUTED_NAME, merchant)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void dismissedLumpedContractDoesNotSurfaceInUnmatchedRecurring() {
    long imp = importId();
    attributed(imp, "fz-1", LocalDate.of(2026, 1, 1), "Fizz Media");
    attributed(imp, "fz-2", LocalDate.of(2026, 2, 1), "Fizz Media");
    counterpartyResolver.resolve();

    // Seed a pre-existing lumped PayPal counterparty + a dismissed auto contract + recurring,
    // as if materialized before V12 (creditor identity, shared mandate, hivemem link null).
    long paypalCp =
        db.fetchOne(
                "INSERT INTO counterparties (identity_type, identity_value, display_name) "
                    + "VALUES ('creditor_id', 'SYNTH-PP-CREDITOR', 'PayPal') RETURNING id")
            .get("id", Long.class);
    long lumped =
        db.fetchOne(
                "INSERT INTO contracts (counterparty_id, mandate_id, source, status) "
                    + "VALUES (?, 'PP-SHARED-MANDATE', 'auto', 'dismissed') RETURNING id",
                paypalCp)
            .get("id", Long.class);
    db.execute(
        "INSERT INTO recurring (counterparty_id, contract_id, cadence, typical_amount, "
            + "amount_min, amount_max, first_seen, last_seen, occurrence_count, source) "
            + "VALUES (?, ?, 'irregular', 99.00, 99.00, 99.00, DATE '2024-01-01', "
            + "DATE '2026-01-01', 84, 'auto')",
        paypalCp,
        lumped);

    contractResolver.resolve();

    var unmatched = readTools.listUnmatchedRecurring(null, null);
    assertThat(unmatched)
        .noneMatch(e -> e.contractId() != null && e.contractId().equals(lumped));
  }
}
