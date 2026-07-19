package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression + pooling coverage for the alias-aware {@code v_counterparty_evidence} /
 * {@code v_contract_evidence} rewrite (V15).
 *
 * <p>{@code evidenceUnchangedWithoutAliases} pins down exact aggregate values that must hold both
 * on the current (V12) views and on the rewritten (V15) views when no alias rows exist -- the
 * un-merged bijection guarantee. {@code evidencePoolsAliasedSource} exercises the new pooling
 * behavior once an alias exists.
 */
class CounterpartyAliasEvidenceIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE counterparties RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private long insertCounterparty(String identityType, String identityValue) {
    return db.fetchOne(
            "INSERT INTO counterparties (identity_type, identity_value, display_name) "
                + "VALUES (?, ?, ?) RETURNING id",
            identityType,
            identityValue,
            identityValue)
        .get("id", Long.class);
  }

  private void insertAlias(String identityType, String identityValue, long canonicalCounterpartyId) {
    db.execute(
        "INSERT INTO counterparty_alias (identity_type, identity_value, canonical_counterparty_id) "
            + "VALUES (?, ?, ?)",
        identityType,
        identityValue,
        canonicalCounterpartyId);
  }

  /** General-purpose transaction insert covering every identity/attribution/split path. */
  private void insertTxn(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String direction,
      String creditorId,
      String iban,
      String name,
      String mandateId,
      String attributedName,
      String splitParentContentHash,
      Integer splitParentOccurrenceIndex) {
    var step =
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
            .set(TRANSACTIONS.MANDATE_ID, mandateId)
            .set(TRANSACTIONS.ATTRIBUTED_NAME, attributedName)
            .set(TRANSACTIONS.ATTRIBUTION_SOURCE, attributedName == null ? null : "paypal")
            .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW));
    if (splitParentContentHash != null) {
      step =
          step.set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, splitParentContentHash)
              .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, splitParentOccurrenceIndex);
    }
    step.execute();
  }

  private Integer counterpartyTxnCount(long counterpartyId) {
    var row =
        db.fetchOne(
            "SELECT txn_count FROM v_counterparty_evidence WHERE counterparty_id = ?",
            counterpartyId);
    return row == null ? null : row.get("txn_count", Integer.class);
  }

  private BigDecimal counterpartyTotalAmount(long counterpartyId) {
    var row =
        db.fetchOne(
            "SELECT total_amount FROM v_counterparty_evidence WHERE counterparty_id = ?",
            counterpartyId);
    return row == null ? null : row.get("total_amount", BigDecimal.class);
  }

  @Test
  void evidenceUnchangedWithoutAliases() {
    long imp = importId();

    // Counterparty A: creditor_id identity, two DBIT txns, one mandate -> also present in
    // v_contract_evidence.
    long a = insertCounterparty("creditor_id", "CDTR-A");
    insertTxn(imp, "hash-a-1", LocalDate.of(2026, 1, 1), "10.00", "DBIT", "CDTR-A", null, null,
        "MANDATE-A", null, null, null);
    insertTxn(imp, "hash-a-2", LocalDate.of(2026, 2, 1), "20.00", "DBIT", "CDTR-A", null, null,
        "MANDATE-A", null, null, null);

    // Counterparty B: iban identity, one CRDT txn.
    long b = insertCounterparty("iban", "IBAN-B");
    insertTxn(imp, "hash-b-1", LocalDate.of(2026, 1, 5), "15.00", "CRDT", null, "IBAN-B", null,
        null, null, null, null);

    // Counterparty C: name identity, one DBIT txn (raw name mixed-case, identity normalized).
    long c = insertCounterparty("name", "COUNTERPARTY C");
    insertTxn(imp, "hash-c-1", LocalDate.of(2026, 1, 10), "5.00", "DBIT", null, null,
        "Counterparty C", null, null, null, null);

    // Split parent+child: root (creditor_id CDTR-D) is superseded by a child booking under a
    // different identity (name Bargeld) -> root drops out of both views entirely.
    long d = insertCounterparty("creditor_id", "CDTR-D");
    long bargeld = insertCounterparty("name", "BARGELD");
    insertTxn(imp, "hash-d-root", LocalDate.of(2026, 1, 15), "80.00", "DBIT", "CDTR-D", null,
        null, "MANDATE-D", null, null, null);
    insertTxn(imp, "hash-d-child", LocalDate.of(2026, 1, 15), "80.00", "DBIT", null, null,
        "Bargeld", null, null, "hash-d-root", 0);

    // Attributed row: creditor_id is a PayPal passthrough, attributed_name carries the real
    // merchant identity -> resolves to a name identity, synthetic 'attributed' mandate.
    long fizz = insertCounterparty("name", "FIZZ MEDIA");
    insertTxn(imp, "hash-fizz-1", LocalDate.of(2026, 1, 20), "12.00", "DBIT", "SYNTH-PP", null,
        "PayPal Europe S.a.r.l.", "PP-MANDATE-1", "Fizz Media", null, null);

    // Identity with NO counterparties row -> must never produce an evidence row.
    insertTxn(imp, "hash-norow-1", LocalDate.of(2026, 1, 25), "99.00", "DBIT", "CDTR-NOROW", null,
        null, "MANDATE-NOROW", null, null, null);

    assertThat(counterpartyTxnCount(a)).isEqualTo(2);
    assertThat(counterpartyTotalAmount(a)).isEqualByComparingTo("30.00");
    assertThat(counterpartyTxnCount(b)).isEqualTo(1);
    assertThat(counterpartyTotalAmount(b)).isEqualByComparingTo("15.00");
    assertThat(counterpartyTxnCount(c)).isEqualTo(1);
    assertThat(counterpartyTotalAmount(c)).isEqualByComparingTo("5.00");
    assertThat(counterpartyTxnCount(d)).isNull(); // root excluded by split-parent NOT EXISTS
    assertThat(counterpartyTxnCount(bargeld)).isEqualTo(1);
    assertThat(counterpartyTotalAmount(bargeld)).isEqualByComparingTo("80.00");
    assertThat(counterpartyTxnCount(fizz)).isEqualTo(1);
    assertThat(counterpartyTotalAmount(fizz)).isEqualByComparingTo("12.00");

    var noRowRows =
        db.fetch(
            "SELECT 1 FROM v_counterparty_evidence v "
                + "WHERE v.counterparty_id NOT IN (SELECT id FROM counterparties)");
    assertThat(noRowRows).isEmpty();

    // v_contract_evidence: only creditor_id (+mandate) or attributed_name rows are grouped.
    var contractA =
        db.fetch(
            "SELECT mandate_id, txn_count FROM v_contract_evidence WHERE counterparty_id = ?", a);
    assertThat(contractA).hasSize(1);
    assertThat(contractA.get(0).get("mandate_id", String.class)).isEqualTo("MANDATE-A");
    assertThat(contractA.get(0).get("txn_count", Integer.class)).isEqualTo(2);

    var contractB =
        db.fetch("SELECT 1 FROM v_contract_evidence WHERE counterparty_id = ?", b);
    assertThat(contractB).isEmpty(); // iban-only identity never keys contract evidence

    var contractD =
        db.fetch("SELECT 1 FROM v_contract_evidence WHERE counterparty_id = ?", d);
    assertThat(contractD).isEmpty(); // root excluded by split-parent filter

    var contractFizz =
        db.fetch(
            "SELECT mandate_id, txn_count FROM v_contract_evidence WHERE counterparty_id = ?",
            fizz);
    assertThat(contractFizz).hasSize(1);
    assertThat(contractFizz.get(0).get("mandate_id", String.class)).isEqualTo("attributed");
    assertThat(contractFizz.get(0).get("txn_count", Integer.class)).isEqualTo(1);

    var contractNoRow =
        db.fetch(
            "SELECT 1 FROM v_contract_evidence v "
                + "WHERE v.counterparty_id NOT IN (SELECT id FROM counterparties)");
    assertThat(contractNoRow).isEmpty();
  }

  @Test
  void evidencePoolsAliasedSource() {
    long imp = importId();

    long a = insertCounterparty("creditor_id", "CDTR-A2");
    insertTxn(imp, "hash-a2-1", LocalDate.of(2026, 3, 1), "10.00", "DBIT", "CDTR-A2", null, null,
        "MANDATE-A2", null, null, null);
    insertTxn(imp, "hash-a2-2", LocalDate.of(2026, 3, 5), "20.00", "DBIT", "CDTR-A2", null, null,
        "MANDATE-A2", null, null, null);

    long b = insertCounterparty("iban", "IBAN-B2");
    insertTxn(imp, "hash-b2-1", LocalDate.of(2026, 3, 10), "15.00", "CRDT", null, "IBAN-B2", null,
        null, null, null, null);

    // B's identity is folded into A.
    insertAlias("iban", "IBAN-B2", a);

    // An identity with neither a counterparties row nor an alias -> still absent.
    insertTxn(imp, "hash-norow2-1", LocalDate.of(2026, 3, 12), "99.00", "DBIT", "CDTR-NOROW2",
        null, null, "MANDATE-NOROW2", null, null, null);

    assertThat(counterpartyTxnCount(b)).isNull(); // B has no evidence row anymore

    assertThat(counterpartyTxnCount(a)).isEqualTo(3); // A's 2 + B's 1 pooled
    assertThat(counterpartyTotalAmount(a)).isEqualByComparingTo("45.00");

    var noRowRows =
        db.fetch(
            "SELECT 1 FROM v_counterparty_evidence v "
                + "WHERE v.counterparty_id NOT IN (SELECT id FROM counterparties)");
    assertThat(noRowRows).isEmpty();
  }
}
