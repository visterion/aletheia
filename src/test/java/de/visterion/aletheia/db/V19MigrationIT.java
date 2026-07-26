package de.visterion.aletheia.db;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V19 introduces the product grain: the {@code product_rules} table, {@code product} columns on
 * {@code transactions} and {@code contracts}, the widened contract key and the rebuilt {@code
 * v_contract_evidence}.
 *
 * <p>All fixtures are hand-invented ({@code CDTR-INSURER}, {@code POLICY-1}, products {@code
 * HEALTH}/{@code LEGAL}); no production creditor, mandate or remittance string exists in this repo.
 */
class V19MigrationIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE contracts, recurring, counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE product_rules RESTART IDENTITY CASCADE");
  }

  private long seedCounterparty(String creditorId) {
    return db.fetchOne(
            "INSERT INTO counterparties (identity_type, identity_value, display_name)"
                + " VALUES ('creditor_id', ?, 'SYNTHETIC INSURER') RETURNING id",
            creditorId)
        .get("id", Long.class);
  }

  // ---------------------------------------------------------------------------------------------
  // product_rules
  // ---------------------------------------------------------------------------------------------

  @Test
  void productRulesTableExistsWithCounters() {
    Record row =
        db.fetchOne(
            "SELECT roots_visited, roots_split, roots_stamped, roots_mismatched, last_resolved_at"
                + " FROM product_rules WHERE false");
    // The columns resolve (otherwise the statement would not parse); no rows are expected.
    assertThat(row).isNull();
  }

  @Test
  void productRuleCreditorIsUnique() {
    db.execute(
        "INSERT INTO product_rules (creditor_id, position_pattern) VALUES (?, ?)",
        "CDTR-INSURER",
        "(?<product>x)(?<amount>y)");
    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO product_rules (creditor_id, position_pattern) VALUES (?, ?)",
                    "CDTR-INSURER",
                    "(?<product>z)(?<amount>y)"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_product_rules_creditor");
  }

  // ---------------------------------------------------------------------------------------------
  // contracts key and CHECKs
  // ---------------------------------------------------------------------------------------------

  @Test
  void contractsAcceptProductAndKeepMandatelessIdentity() {
    long cp = seedCounterparty("CDTR-INSURER");
    db.execute(
        "INSERT INTO contracts (counterparty_id, mandate_id, product) VALUES (?, ?, ?)",
        cp,
        "POLICY-1",
        "HEALTH");
    db.execute(
        "INSERT INTO contracts (counterparty_id, mandate_id, product) VALUES (?, ?, ?)",
        cp,
        "POLICY-1",
        "LEGAL");
    db.execute("INSERT INTO contracts (counterparty_id, mandate_id) VALUES (?, null)", cp);

    assertThat(db.fetchCount(CONTRACTS, CONTRACTS.COUNTERPARTY_ID.eq(cp))).isEqualTo(3);
  }

  @Test
  void secondMandatelessContractStillRejected() {
    long cp = seedCounterparty("CDTR-INSURER");
    db.execute("INSERT INTO contracts (counterparty_id, mandate_id) VALUES (?, null)", cp);

    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO contracts (counterparty_id, mandate_id) VALUES (?, null)", cp))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_contract_counterparty_mandate_product");
  }

  @Test
  void sameProductTwiceOnOneMandateRejected() {
    long cp = seedCounterparty("CDTR-INSURER");
    db.execute(
        "INSERT INTO contracts (counterparty_id, mandate_id, product) VALUES (?, ?, ?)",
        cp,
        "POLICY-1",
        "HEALTH");

    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO contracts (counterparty_id, mandate_id, product) VALUES (?, ?, ?)",
                    cp,
                    "POLICY-1",
                    "HEALTH"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_contract_counterparty_mandate_product");
  }

  @Test
  void productWithoutMandateIsRejected() {
    long cp = seedCounterparty("CDTR-INSURER");

    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO contracts (counterparty_id, mandate_id, product)"
                        + " VALUES (?, null, ?)",
                    cp,
                    "HEALTH"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_contracts_product_needs_mandate");
  }

  @Test
  void nonNormalizedContractProductIsRejected() {
    long cp = seedCounterparty("CDTR-INSURER");

    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO contracts (counterparty_id, mandate_id, product) VALUES (?, ?, ?)",
                    cp,
                    "POLICY-1",
                    " health "))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_contracts_product_normalized");
  }

  @Test
  void nonNormalizedTransactionProductIsRejected() {
    long imp = seedImport();

    assertThatThrownBy(() -> insertBooking(imp, LocalDate.of(2026, 1, 15), "10.00", " health "))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_transactions_product_normalized");
  }

  // ---------------------------------------------------------------------------------------------
  // v_contract_evidence rebuild
  // ---------------------------------------------------------------------------------------------

  /**
   * The gap series must be partitioned by product. Two sibling children of one booking share a
   * booking date; without {@code product} in the {@code LAG} window they land in one series, the
   * intra-booking gap of 0 dominates and {@code median_gap_days} collapses to 0 -- invisible to any
   * count or sum assertion.
   */
  @Test
  void contractEvidenceIsGroupedAndPartitionedByProduct() {
    seedCounterparty("CDTR-INSURER");
    long imp = seedImport();
    splitBooking(imp, LocalDate.of(2026, 1, 15), "p1");
    splitBooking(imp, LocalDate.of(2026, 2, 15), "p2");

    Record health = evidenceRow("HEALTH");
    Record legal = evidenceRow("LEGAL");

    assertThat(health.get("txn_count", Long.class)).isEqualTo(2L);
    assertThat(legal.get("txn_count", Long.class)).isEqualTo(2L);
    assertThat(health.get("amount_max", BigDecimal.class)).isEqualByComparingTo("100.00");
    assertThat(legal.get("amount_max", BigDecimal.class)).isEqualByComparingTo("50.00");
    assertThat(health.get("median_gap_days", Double.class)).isEqualTo(31.0d);
    assertThat(legal.get("median_gap_days", Double.class)).isEqualTo(31.0d);

    // The parents are superseded by their children and contribute no row of their own.
    assertThat(
            db.fetchCount(
                db.selectFrom("v_contract_evidence").where("mandate_id = 'POLICY-1'")))
        .isEqualTo(2);
  }

  private Record evidenceRow(String product) {
    Record row =
        db.fetchOne(
            "SELECT txn_count, amount_max, median_gap_days FROM v_contract_evidence"
                + " WHERE mandate_id = 'POLICY-1' AND product = ?",
            product);
    assertThat(row).as("evidence row for product %s", product).isNotNull();
    return row;
  }

  // ---------------------------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------------------------

  private long seedImport() {
    return db.fetchOne(
            "INSERT INTO imports (file_name, file_sha256) VALUES ('synthetic.json', ?)"
                + " RETURNING id",
            "sha-" + UUID.randomUUID())
        .get("id", Long.class);
  }

  /** One 150,00 booking split into a 100,00 HEALTH and a 50,00 LEGAL child on the same date. */
  private void splitBooking(long importId, LocalDate date, String tag) {
    String parentHash = "parent-" + tag;
    insertBooking(importId, date, "150.00", null, parentHash, null, null);
    insertBooking(importId, date, "100.00", "HEALTH", "child-health-" + tag, parentHash, 0);
    insertBooking(importId, date, "50.00", "LEGAL", "child-legal-" + tag, parentHash, 0);
  }

  private void insertBooking(long importId, LocalDate date, String amount, String product) {
    insertBooking(importId, date, amount, product, "hash-" + UUID.randomUUID(), null, null);
  }

  private void insertBooking(
      long importId,
      LocalDate date,
      String amount,
      String product,
      String contentHash,
      String parentHash,
      Integer parentOccurrence) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, creditor_id, mandate_id, product,"
            + " split_parent_content_hash, split_parent_occurrence_index, raw)"
            + " VALUES (?, 0, ?, ?, ?, 'EUR', 'DBIT', 'BOOK', 'CDTR-INSURER', 'POLICY-1', ?,"
            + " ?, ?, '{}'::jsonb)",
        contentHash,
        importId,
        date,
        new BigDecimal(amount),
        product,
        parentHash,
        parentOccurrence);
  }
}
