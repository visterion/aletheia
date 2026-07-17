package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.mcp.TxReference;
import de.visterion.aletheia.mcp.WriteTools;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "aletheia.paypal.creditor-ids=SYNTH-PP-CREDITOR")
class ReattributePayPalTransienceIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired PayPalAttributionResolver payPalAttributionResolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparties, transactions, imports"
            + " RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void insert(long imp, String hash, String creditorId, String remittance) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.REMITTANCE_INFO, remittance)
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  private String source(String hash) {
    return (String)
        db.fetchValue("SELECT attribution_source FROM transactions WHERE content_hash = ?", hash);
  }

  @Test
  void clearOnPayPalRowIsTransientButAdyenStaysCleared() {
    long imp = importId();
    // PayPal-parseable remittance (format the #29 resolver understands).
    insert(imp, "pp", "SYNTH-PP-CREDITOR", ". Fizz Media, Ihr Einkauf bei Fizz Media");
    insert(imp, "ad", "SYNTH-ADYEN", "Fizz Media");
    payPalAttributionResolver.resolve(); // stamps the PayPal row 'paypal'
    writeTools.reattributeTransaction(List.of(new TxReference("ad", 0)), "Fizz Media");

    // Clear both.
    writeTools.reattributeTransaction(
        List.of(new TxReference("pp", 0), new TxReference("ad", 0)), null);
    assertThat(source("pp")).isNull();
    assertThat(source("ad")).isNull();

    // Next resolver pass re-stamps only the PayPal row.
    payPalAttributionResolver.resolve();
    assertThat(source("pp")).isEqualTo("paypal");
    assertThat(source("ad")).isNull();
  }

  @Test
  void manualOverrideOnPayPalRowSurvivesResolve() {
    long imp = importId();
    insert(imp, "pp", "SYNTH-PP-CREDITOR", ". Fizz Media, Ihr Einkauf bei Fizz Media");
    payPalAttributionResolver.resolve();
    assertThat(source("pp")).isEqualTo("paypal");

    // Manually correct it to a different merchant: manual wins.
    writeTools.reattributeTransaction(List.of(new TxReference("pp", 0)), "Pixel Games");
    assertThat(source("pp")).isEqualTo("manual");
    assertThat(attributedName("pp")).isEqualTo("Pixel Games");

    // The deterministic resolver never clobbers a manual override.
    payPalAttributionResolver.resolve();
    assertThat(source("pp")).isEqualTo("manual");
    assertThat(attributedName("pp")).isEqualTo("Pixel Games");
  }

  private String attributedName(String hash) {
    return (String)
        db.fetchValue("SELECT attributed_name FROM transactions WHERE content_hash = ?", hash);
  }
}
