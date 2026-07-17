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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PayPalCounterpartyMergeIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;

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

  private void insert(long imp, String hash, LocalDate date, String creditorId, String name,
      String attributedName) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, date)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.ATTRIBUTED_NAME, attributedName)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, attributedName == null ? null : "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void attributedRowBecomesMerchantCounterpartyNotPaypal() {
    long imp = importId();
    insert(imp, "a1", LocalDate.of(2026, 1, 1), "SYNTH-PP-CREDITOR",
        "PayPal Europe S.a.r.l.", "Fizz Media");

    resolver.resolve();

    var rows =
        db.select(COUNTERPARTIES.IDENTITY_TYPE, COUNTERPARTIES.IDENTITY_VALUE,
                COUNTERPARTIES.DISPLAY_NAME)
            .from(COUNTERPARTIES)
            .fetch();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get(COUNTERPARTIES.IDENTITY_TYPE)).isEqualTo("name");
    assertThat(rows.get(0).get(COUNTERPARTIES.IDENTITY_VALUE)).isEqualTo("FIZZ MEDIA");
    assertThat(rows.get(0).get(COUNTERPARTIES.DISPLAY_NAME)).isEqualTo("Fizz Media");
  }

  @Test
  void directAndPaypalReachedMerchantMergeIntoOneCounterparty() {
    long imp = importId();
    // Direct: name identity from counterparty_name.
    insert(imp, "d1", LocalDate.of(2026, 1, 1), null, "Acme Streaming", null);
    // Via PayPal: attributed_name = same merchant spelling.
    insert(imp, "p1", LocalDate.of(2026, 2, 1), "SYNTH-PP-CREDITOR",
        "PayPal Europe S.a.r.l.", "Acme Streaming");

    resolver.resolve();

    var rows =
        db.select(COUNTERPARTIES.IDENTITY_TYPE, COUNTERPARTIES.IDENTITY_VALUE,
                COUNTERPARTIES.DISPLAY_NAME)
            .from(COUNTERPARTIES)
            .fetch();
    assertThat(rows).hasSize(1); // merged
    assertThat(rows.get(0).get(COUNTERPARTIES.IDENTITY_VALUE)).isEqualTo("ACME STREAMING");
    assertThat(rows.get(0).get(COUNTERPARTIES.DISPLAY_NAME)).isEqualTo("Acme Streaming");
  }
}
