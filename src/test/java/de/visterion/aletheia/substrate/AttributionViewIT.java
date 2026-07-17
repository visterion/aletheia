package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AttributionViewIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

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

  private void insertAttributed(long imp, String hash, LocalDate date, String amount,
      String creditorId, String mandateId, String attributedName) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, date)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "PayPal Europe S.a.r.l.")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.ATTRIBUTED_NAME, attributedName)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, attributedName == null ? null : "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void bothOrNeitherConstraintRejectsHalfSetAttribution() {
    long imp = importId();
    assertThatThrownBy(
            () ->
                db.insertInto(TRANSACTIONS)
                    .set(TRANSACTIONS.CONTENT_HASH, "half-set")
                    .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
                    .set(TRANSACTIONS.IMPORT_ID, imp)
                    .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
                    .set(TRANSACTIONS.AMOUNT, new BigDecimal("1.00"))
                    .set(TRANSACTIONS.CURRENCY, "EUR")
                    .set(TRANSACTIONS.DIRECTION, "DBIT")
                    .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
                    .set(TRANSACTIONS.ATTRIBUTED_NAME, "Fizz Media") // source NULL -> violation
                    .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
                    .execute())
        .hasMessageContaining("chk_transactions_attribution_pair");
  }

  @Test
  void counterpartyEvidenceDerivesMerchantIdentityForAttributedRows() {
    long imp = importId();
    // Two attributed PayPal rows for the same merchant -> ONE name-identity counterparty.
    insertAttributed(imp, "att-1", LocalDate.of(2026, 1, 1), "10.00",
        "SYNTH-PP-CREDITOR", "PP-MANDATE-1", "Fizz Media");
    insertAttributed(imp, "att-2", LocalDate.of(2026, 2, 1), "10.00",
        "SYNTH-PP-CREDITOR", "PP-MANDATE-1", "Fizz Media");
    // Create the name counterparty the view will join to.
    db.execute(
        "INSERT INTO counterparties (identity_type, identity_value, display_name) "
            + "VALUES ('name', 'FIZZ MEDIA', 'Fizz Media')");

    var rows =
        db.fetch(
            "SELECT v.txn_count FROM v_counterparty_evidence v "
                + "JOIN counterparties c ON c.id = v.counterparty_id "
                + "WHERE c.identity_value = 'FIZZ MEDIA'");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("txn_count", Integer.class)).isEqualTo(2);
  }

  @Test
  void contractEvidenceKeysAttributedRowsToSyntheticAttributedMandate() {
    long imp = importId();
    insertAttributed(imp, "att-c1", LocalDate.of(2026, 1, 1), "10.00",
        "SYNTH-PP-CREDITOR", "PP-MANDATE-1", "Acme Streaming");
    insertAttributed(imp, "att-c2", LocalDate.of(2026, 2, 1), "10.00",
        "SYNTH-PP-CREDITOR", "PP-MANDATE-1", "Acme Streaming");
    db.execute(
        "INSERT INTO counterparties (identity_type, identity_value, display_name) "
            + "VALUES ('name', 'ACME STREAMING', 'Acme Streaming')");

    var rows =
        db.fetch(
            "SELECT v.mandate_id, v.txn_count FROM v_contract_evidence v "
                + "JOIN counterparties c ON c.id = v.counterparty_id "
                + "WHERE c.identity_value = 'ACME STREAMING'");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("mandate_id", String.class)).isEqualTo("attributed");
    assertThat(rows.get(0).get("txn_count", Integer.class)).isEqualTo(2);
  }
}
