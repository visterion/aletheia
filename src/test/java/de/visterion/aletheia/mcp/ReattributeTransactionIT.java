package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReattributeTransactionIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired CounterpartyResolver counterpartyResolver;

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

  /** A raw Adyen passthrough booking (no attribution yet). Returns its content_hash. */
  private String insertAdyen(long imp, String hash, String remittance) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-ADYEN")
        .set(TRANSACTIONS.REMITTANCE_INFO, remittance)
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
    return hash;
  }

  private String attribution(String hash) {
    return (String)
        db.fetchValue(
            "SELECT attribution_source FROM transactions WHERE content_hash = ?", hash);
  }

  private String attributedName(String hash) {
    return (String)
        db.fetchValue("SELECT attributed_name FROM transactions WHERE content_hash = ?", hash);
  }

  @Test
  void setModeStampsManualAndMaterializesMerchant() {
    long imp = importId();
    insertAdyen(imp, "a1", "Zahlung Fizz Media");
    insertAdyen(imp, "a2", "Zahlung Fizz Media");

    BatchWriteAck ack =
        writeTools.reattributeTransaction(
            List.of(new TxReference("a1", 0), new TxReference("a2", 0)), "Fizz Media");

    assertThat(ack.affectedCount()).isEqualTo(2);
    assertThat(attribution("a1")).isEqualTo("manual");
    assertThat(attributedName("a1")).isEqualTo("Fizz Media");
    Number merchantExists =
        (Number)
            db.fetchValue(
                "SELECT count(*) FROM counterparties WHERE identity_type = 'name'"
                    + " AND identity_value = 'FIZZ MEDIA'");
    assertThat(merchantExists.intValue()).isEqualTo(1);
  }

  @Test
  void adyenFansOutIntoDistinctMerchants() {
    long imp = importId();
    insertAdyen(imp, "f1", "Fizz Media");
    insertAdyen(imp, "f2", "Fizz Media");
    insertAdyen(imp, "p1", "Pixel Games");
    insertAdyen(imp, "u1", "unclassified");

    writeTools.reattributeTransaction(
        List.of(new TxReference("f1", 0), new TxReference("f2", 0)), "Fizz Media");
    writeTools.reattributeTransaction(List.of(new TxReference("p1", 0)), "Pixel Games");

    Number names =
        (Number)
            db.fetchValue(
                "SELECT count(*) FROM counterparties WHERE identity_type = 'name'"
                    + " AND identity_value IN ('FIZZ MEDIA', 'PIXEL GAMES')");
    assertThat(names.intValue()).isEqualTo(2);
    // The unattributed remainder still resolves under the Adyen creditor.
    Number adyen =
        (Number)
            db.fetchValue(
                "SELECT count(*) FROM counterparties WHERE identity_type = 'creditor_id'"
                    + " AND identity_value = 'SYNTH-ADYEN'");
    assertThat(adyen.intValue()).isEqualTo(1);
  }

  @Test
  void countIsZeroOnNoOpRewrite() {
    long imp = importId();
    insertAdyen(imp, "n1", "Fizz Media");
    writeTools.reattributeTransaction(List.of(new TxReference("n1", 0)), "Fizz Media");
    BatchWriteAck again =
        writeTools.reattributeTransaction(List.of(new TxReference("n1", 0)), "Fizz Media");
    assertThat(again.affectedCount()).isZero();
  }

  @Test
  void clearModeRemovesAttribution() {
    long imp = importId();
    insertAdyen(imp, "c1", "Fizz Media");
    writeTools.reattributeTransaction(List.of(new TxReference("c1", 0)), "Fizz Media");

    BatchWriteAck ack = writeTools.reattributeTransaction(List.of(new TxReference("c1", 0)), null);
    assertThat(ack.affectedCount()).isEqualTo(1);
    assertThat(attributedName("c1")).isNull();
    assertThat(attribution("c1")).isNull();
  }

  @Test
  void rejectsUnknownRefAtomically() {
    long imp = importId();
    insertAdyen(imp, "ok1", "Fizz Media");
    assertThatThrownBy(
            () ->
                writeTools.reattributeTransaction(
                    List.of(new TxReference("ok1", 0), new TxReference("missing", 0)),
                    "Fizz Media"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no such transaction: missing#0");
    // Batch-atomic: the valid ref was not written either.
    assertThat(attribution("ok1")).isNull();
  }

  @Test
  void rejectsBlankNameButNotNull() {
    long imp = importId();
    insertAdyen(imp, "b1", "Fizz Media");
    assertThatThrownBy(
            () -> writeTools.reattributeTransaction(List.of(new TxReference("b1", 0)), "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void rejectsEmptyRefs() {
    assertThatThrownBy(() -> writeTools.reattributeTransaction(List.of(), "Fizz Media"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("refs must be non-empty");
  }
}
