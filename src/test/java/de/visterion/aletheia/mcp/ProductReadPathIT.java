package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import de.visterion.aletheia.substrate.ProductSplitResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read paths at {@code (counterparty_id, mandate_id, product)} grain (spec §7).
 *
 * <p>The three {@code v_contract_evidence} joins in {@link ReadTools} matched on {@code
 * (counterparty_id, mandate_id)} only. Once the view groups by product as well, one contract row
 * joins <em>every</em> product evidence row of its mandate: the register emits a confirmed
 * obligation once per product, each with a different cost, and {@code totalAnnualCost} inflates.
 * That is a regression on already-confirmed data and it lands exactly in the rollout window of
 * spec §8 steps 4-5, where the ended/confirmed mandate contract and the new product contracts
 * coexist on one mandate.
 *
 * <p>All fixtures are hand-invented: {@code CDTR-INSURER}, {@code SYNTHETIC INSURER}, {@code
 * POLICY-1}, products {@code Health}/{@code Legal}. No production creditor id, mandate reference
 * or remittance string exists in this repository.
 */
class ProductReadPathIT extends AbstractPostgresIT {

  private static final String CREDITOR = "CDTR-INSURER";
  private static final String MANDATE = "POLICY-1";

  /** The spec's synthetic illustration; identical to the one {@code ProductSplitResolverIT} uses. */
  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Two positions summing to 150.00: the bundled-mandate case. */
  private static final String MULTI = "POLICY-1 Health 100,00 Legal 50,00";

  /** aggregate() has no open-ended range: a NULL bound matches nothing. */
  private static final LocalDate WINDOW_FROM = LocalDate.now().minusYears(2);

  private static final LocalDate WINDOW_TO = LocalDate.now().plusDays(1);

  @Autowired DSLContext db;
  @Autowired ReadTools readTools;
  @Autowired ObjectMapper mapper;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ProductSplitResolver productSplitResolver;
  @Autowired ContractResolver contractResolver;

  private long importId;

  @BeforeEach
  void seedImportRow() {
    importId =
        db.fetchOne(
                "INSERT INTO imports (file_name, file_sha256) VALUES ('synthetic.json', ?)"
                    + " RETURNING id",
                "sha-" + UUID.randomUUID())
            .get("id", Long.class);
  }

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags,"
            + " counterparty_alias, counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE product_rules RESTART IDENTITY CASCADE");
  }

  /**
   * The rollout window of spec §8 steps 4-5: the superseded mandate contract is still confirmed
   * while the product contracts have just appeared. Every confirmed contract must produce exactly
   * one register row carrying its own cost, and the total must be the sum of those distinct rows.
   */
  @Test
  void confirmedMandateContractAndOpenProductContractsCoexistWithoutFanout() {
    // Step 1-3 of the rollout: no rule yet, so the mandate is one lumped contract, and a human has
    // confirmed it.
    seedTwoMonthlyMultiProductBookings();
    settle();
    confirmAllContracts();

    ObligationsRegister beforeRule = readTools.obligationsRegister(null);
    assertThat(beforeRule.rows()).hasSize(1);
    assertThat(beforeRule.totalAnnualCost())
        .isEqualByComparingTo(beforeRule.rows().get(0).annualCost());

    // Step 4: the rule is enabled, the resolver settles, product contracts appear as `open`.
    seedRule();
    settle();
    assertThat(openProductContractCount()).isEqualTo(2);

    ObligationsRegister window = readTools.obligationsRegister(null);
    // Still exactly one confirmed obligation. Without the product predicate the confirmed
    // mandate contract joins BOTH product evidence rows and this is 2 rows with the same
    // contractId, at two different costs.
    assertThat(window.rows()).hasSize(1);
    assertThat(window.rows()).extracting(ObligationRow::contractId).doesNotHaveDuplicates();
    assertThat(window.totalAnnualCost())
        .isEqualByComparingTo(window.rows().get(0).annualCost());

    // Step 5: the human confirms the product contracts too.
    confirmAllContracts();
    ObligationsRegister after = readTools.obligationsRegister(null);
    assertThat(after.rows()).hasSize(3);
    assertThat(after.rows()).extracting(ObligationRow::contractId).doesNotHaveDuplicates();
    assertThat(after.totalAnnualCost())
        .isEqualByComparingTo(
            after.rows().stream()
                .map(ObligationRow::annualCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

    // Each product row carries its OWN cost, not the mandate lump.
    BigDecimal health = annualCostOfProduct(after, "HEALTH");
    BigDecimal legal = annualCostOfProduct(after, "LEGAL");
    assertThat(health).isGreaterThan(legal);
    assertThat(health.add(legal))
        .isEqualByComparingTo(annualCostOfProduct(after, null));
  }

  @Test
  void reviewQueueHasNoDuplicates() {
    seedRule();
    seedTwoMonthlyMultiProductBookings();
    settle();

    List<ReviewQueueEntry> queue = readTools.getReviewQueue(100, false);

    // Two open product contracts, two rows. A mandate-only join yields four (each contract row
    // joined against both product evidence rows).
    assertThat(queue).hasSize(2);
    assertThat(queue).extracting(ReviewQueueEntry::contractId).doesNotHaveDuplicates();
    assertThat(queue).extracting(ReviewQueueEntry::product)
        .containsExactlyInAnyOrder("HEALTH", "LEGAL");
    assertThat(queue).extracting(ReviewQueueEntry::annualCostEstimate).doesNotHaveDuplicates();
  }

  /** A mandate with no rule must serialize exactly as before: no {@code product} key at all. */
  @Test
  void productOmittedWhenNull() {
    seedTwoMonthlyMultiProductBookings();
    settle();
    confirmAllContracts();

    JsonNode lump = serialize(readTools.obligationsRegister(null).rows().get(0));
    assertThat(lump.has("product")).isFalse();

    List<ReviewQueueEntry> queueBefore = readTools.getReviewQueue(100, false);
    assertThat(queueBefore).isEmpty(); // the only contract is confirmed, not open

    seedRule();
    settle();

    JsonNode openProduct =
        serialize(
            readTools.getReviewQueue(100, false).stream()
                .filter(e -> "HEALTH".equals(e.product()))
                .findFirst()
                .orElseThrow());
    assertThat(openProduct.get("product").asString()).isEqualTo("HEALTH");

    // The still-confirmed mandate contract keeps its product-less shape alongside them.
    assertThat(serialize(readTools.obligationsRegister(null).rows().get(0)).has("product"))
        .isFalse();
  }

  /**
   * V19 added {@code product} to the evidence view's {@code LAG ... PARTITION BY}. Without it the
   * same-date sibling children of one split booking share a single gap series, the intra-booking
   * gap of 0 dominates and {@code median_gap_days} collapses to 0 for every product contract.
   * Count and sum assertions cannot see this, so the cadence signal is asserted directly.
   */
  @Test
  void medianGapDaysIsSaneForAProductContract() {
    seedRule();
    for (int month = 0; month < 4; month++) {
      seedBooking("m" + month, LocalDate.now().minusDays(5L + 30L * month), "150.00", MULTI);
    }
    settle();

    for (String product : List.of("HEALTH", "LEGAL")) {
      Record row =
          db.fetchOne(
              "SELECT median_gap_days FROM v_contract_evidence WHERE mandate_id = ?"
                  + " AND product = ?",
              MANDATE,
              product);
      assertThat(row).isNotNull();
      assertThat(row.get("median_gap_days", BigDecimal.class))
          .as("median gap for %s", product)
          .isEqualByComparingTo("30");
    }
  }

  /**
   * Spec §2's correction to D3: the split writes children onto the parent's own counterparty, so
   * {@code effective_cp} is unchanged and every SUM stays identical -- but the reads are
   * leaf-based, so row counts do rise. Asserted rather than believed.
   */
  @Test
  void aggregateSumsUnchangedButCountMetricRises() {
    seedTwoMonthlyMultiProductBookings();
    settle();
    long counterpartyId =
        db.fetchOne("SELECT id FROM counterparties WHERE identity_value = ?", CREDITOR)
            .get("id", Long.class);

    BigDecimal sumBefore = totalSum(counterpartyId);
    BigDecimal countBefore = totalCount(counterpartyId);
    int txnRowsBefore = readTools.counterpartyTransactions(counterpartyId, null, null, null).size();

    seedRule();
    settle();

    assertThat(totalSum(counterpartyId)).isEqualByComparingTo(sumBefore);
    assertThat(totalCount(counterpartyId)).isGreaterThan(countBefore);
    assertThat(countBefore).isEqualByComparingTo("2");
    assertThat(totalCount(counterpartyId)).isEqualByComparingTo("4");
    assertThat(readTools.counterpartyTransactions(counterpartyId, null, null, null))
        .hasSize(txnRowsBefore * 2);
  }

  // -------------------------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------------------------

  /** The production settle order: counterparties, then products, then contracts. */
  private void settle() {
    counterpartyResolver.resolve();
    productSplitResolver.resolve();
    contractResolver.resolve();
  }

  private JsonNode serialize(Object row) {
    return mapper.readTree(mapper.writeValueAsString(row));
  }

  private BigDecimal totalSum(long counterpartyId) {
    return readTools
        .aggregate(
            WINDOW_FROM,
            WINDOW_TO,
            AggregateGroupBy.TOTAL,
            AggregateMetric.SUM,
            Direction.DBIT,
            false,
            List.of(counterpartyId),
            null)
        .get(0)
        .value();
  }

  private BigDecimal totalCount(long counterpartyId) {
    return readTools
        .aggregate(
            WINDOW_FROM,
            WINDOW_TO,
            AggregateGroupBy.TOTAL,
            AggregateMetric.COUNT,
            Direction.DBIT,
            false,
            List.of(counterpartyId),
            null)
        .get(0)
        .value();
  }

  private BigDecimal annualCostOfProduct(ObligationsRegister register, String product) {
    return register.rows().stream()
        .filter(r -> product == null ? r.product() == null : product.equals(r.product()))
        .findFirst()
        .orElseThrow()
        .annualCost();
  }

  private long openProductContractCount() {
    return db.fetchOne(
            "SELECT count(*) AS c FROM contracts WHERE status = 'open' AND product IS NOT NULL")
        .get("c", Long.class);
  }

  private void confirmAllContracts() {
    db.execute("UPDATE contracts SET status = 'confirmed', source = 'confirmed'");
  }

  private void seedRule() {
    db.execute(
        "INSERT INTO product_rules (creditor_id, position_pattern) VALUES (?, ?)",
        CREDITOR,
        PATTERN);
  }

  private void seedTwoMonthlyMultiProductBookings() {
    seedBooking("jan", LocalDate.now().minusDays(5), "150.00", MULTI);
    seedBooking("feb", LocalDate.now().minusDays(35), "150.00", MULTI);
  }

  private void seedBooking(String tag, LocalDate date, String amount, String remittance) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, mandate_id, raw)"
            + " VALUES (?, 0, ?, ?, ?, 'EUR', 'DBIT', 'BOOK', ?, 'SYNTHETIC INSURER', ?, ?,"
            + " '{}'::jsonb)",
        "root-" + tag,
        importId,
        date,
        new BigDecimal(amount),
        remittance,
        CREDITOR,
        MANDATE);
  }
}
