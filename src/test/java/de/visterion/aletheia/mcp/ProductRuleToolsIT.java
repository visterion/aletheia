package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The five product-rule MCP tools end to end (spec §5 lifecycle, §7 tools).
 *
 * <p>These are ITs and not unit tests for the same reason {@code ProductSplitResolverIT} is: every
 * assertion here depends on what the resolver actually wrote, and product identity is the SQL normal
 * form Java cannot reproduce.
 *
 * <p>All fixtures are hand-invented -- {@code CDTR-INSURER}, {@code SYNTHETIC INSURER}, {@code
 * POLICY-1}, products {@code Health}/{@code Legal}/{@code Travel}. No production creditor id,
 * mandate reference or remittance format exists in this repository.
 */
class ProductRuleToolsIT extends AbstractPostgresIT {

  private static final String CREDITOR = "CDTR-INSURER";
  private static final String MANDATE = "POLICY-1";
  private static final String OTHER_MANDATE = "POLICY-2";

  /** The spec's synthetic illustration; identical to the one the resolver ITs use. */
  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Same positions, but the policy group captures only the alphabetic prefix ({@code SUB}). */
  private static final String PATTERN_SHORT_POLICY =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z]+)-[0-9]+\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Two positions summing to 150.00: the bundled-mandate case. */
  private static final String MULTI = "POLICY-1 Health 100,00 Legal SUB-2 50,00";

  /** One position summing to 90.00: the stamp case, on a second mandate of the same creditor. */
  private static final String SINGLE = "POLICY-2 Travel 90,00";

  /** Two positions that do not sum to the booking amount: the residue case. */
  private static final String MISMATCH = "POLICY-1 Health 100,00 Legal 40,00";

  @Autowired DSLContext db;
  @Autowired ProductRuleService productRuleService;
  @Autowired WriteTools writeTools;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;

  private long importId;

  @BeforeEach
  void reset() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_history, counterparty_tags,"
            + " counterparty_alias, tag_rules, product_rules, counterparties, transactions,"
            + " imports RESTART IDENTITY CASCADE");
    importId =
        db.fetchOne(
                "INSERT INTO imports (file_name, file_sha256) VALUES ('synthetic.json', ?)"
                    + " RETURNING id",
                "sha-" + UUID.randomUUID())
            .get("id", Long.class);
  }

  // -----------------------------------------------------------------------------------------
  // dryRun
  // -----------------------------------------------------------------------------------------

  @Test
  void dryRunWritesNothing() {
    seedBundledMandate();
    seedStampedBooking();
    settleSubstrate();

    ProductRuleAck ack = productRuleService.createProductRule(CREDITOR, PATTERN, "preview", true);

    assertThat(ack.dryRun()).isTrue();
    assertThat(ack.ruleId()).isNull();
    assertThat(db.fetchCount(org.jooq.impl.DSL.table("product_rules"))).isZero();
    assertThat(childCount()).isZero();
    assertThat(stampedRootCount()).isZero();
  }

  @Test
  void dryRunReportsBlastRadius() {
    seedBundledMandate(); // two matching roots, two positions each
    seedStampedBooking(); // one matching root, one position
    seedBooking("root-mismatch", LocalDate.of(2026, 3, 15), "150.00", MISMATCH, MANDATE);
    settleSubstrate();

    ProductRuleAck ack = productRuleService.createProductRule(CREDITOR, PATTERN, null, true);

    ProductRuleBlastRadius radius = ack.blastRadius();
    assertThat(radius).isNotNull();
    assertThat(radius.rootsVisited()).isEqualTo(4);
    assertThat(radius.bookingsMatched()).isEqualTo(3);
    assertThat(radius.positionsParsed()).isEqualTo(5);
    assertThat(radius.sumMismatches()).isEqualTo(1);
  }

  // -----------------------------------------------------------------------------------------
  // settle triggers
  // -----------------------------------------------------------------------------------------

  /**
   * A rule that is created must take effect in the same call. Ingest here is a manual file upload,
   * so without the settle the operator would wait until the next one -- possibly weeks (spec §5,
   * review Minor 4).
   */
  @Test
  void createSettlesImmediately() {
    seedBundledMandate();
    settleSubstrate();

    ProductRuleAck ack = productRuleService.createProductRule(CREDITOR, PATTERN, null, false);

    assertThat(ack.ruleId()).isNotNull();
    assertThat(ack.dryRun()).isFalse();
    assertThat(childProducts()).containsExactly("HEALTH", "HEALTH", "LEGAL", "LEGAL");
    assertThat(contractProducts())
        .withFailMessage(
            "no product contract exists after create_product_rule: the tool did not settle the"
                + " substrate, so the products would only appear at the next ingest or restart")
        .containsExactly("HEALTH", "LEGAL");
  }

  @Test
  void enableSettlesImmediately() {
    seedBundledMandate();
    settleSubstrate();
    long ruleId = seedDisabledRule();
    assertThat(childCount()).isZero();

    productRuleService.setProductRuleEnabled(ruleId, true);

    assertThat(childProducts()).containsExactly("HEALTH", "HEALTH", "LEGAL", "LEGAL");
    assertThat(contractProducts())
        .withFailMessage(
            "no product contract exists after set_product_rule_enabled(true): the tool did not"
                + " settle the substrate")
        .containsExactly("HEALTH", "LEGAL");
  }

  // -----------------------------------------------------------------------------------------
  // update
  // -----------------------------------------------------------------------------------------

  /**
   * An edit is a graceful refresh, never the delete-and-recreate revert: the auto contracts derived
   * from the previous children must survive, or every rule tweak would drop the human-facing
   * obligations and re-derive them from scratch.
   */
  @Test
  void updateRefreshesWithoutFullRevert() {
    seedBundledMandate();
    settleSubstrate();
    long ruleId = productRuleService.createProductRule(CREDITOR, PATTERN, null, false).ruleId();
    assertThat(policyNumbers()).containsExactly(null, null, "SUB-2", "SUB-2");
    List<Long> contractIdsBefore = contractIds();
    assertThat(contractIdsBefore).hasSize(2);

    ProductRuleAck ack =
        productRuleService.updateProductRule(ruleId, PATTERN_SHORT_POLICY, "narrowed", false);

    assertThat(ack.ruleId()).isEqualTo(ruleId);
    assertThat(policyNumbers()).containsExactly(null, null, "SUB", "SUB");
    assertThat(childProducts()).containsExactly("HEALTH", "HEALTH", "LEGAL", "LEGAL");
    assertThat(contractIds())
        .withFailMessage("update_product_rule deleted and re-derived the product contracts")
        .isEqualTo(contractIdsBefore);
    assertThat(rulePattern(ruleId)).isEqualTo(PATTERN_SHORT_POLICY);
    assertThat(ruleNotes(ruleId)).isEqualTo("narrowed");
  }

  @Test
  void updateDryRunChangesNothing() {
    seedBundledMandate();
    settleSubstrate();
    long ruleId = productRuleService.createProductRule(CREDITOR, PATTERN, null, false).ruleId();

    ProductRuleAck ack =
        productRuleService.updateProductRule(ruleId, PATTERN_SHORT_POLICY, "narrowed", true);

    assertThat(ack.dryRun()).isTrue();
    assertThat(ack.blastRadius()).isNotNull();
    assertThat(rulePattern(ruleId)).isEqualTo(PATTERN);
    assertThat(policyNumbers()).containsExactly(null, null, "SUB-2", "SUB-2");
  }

  // -----------------------------------------------------------------------------------------
  // delete
  // -----------------------------------------------------------------------------------------

  @Test
  void deleteRevertsChildrenStampsAndAutoContracts() {
    seedBundledMandate();
    seedStampedBooking();
    settleSubstrate();
    long ruleId = productRuleService.createProductRule(CREDITOR, PATTERN, null, false).ruleId();
    assertThat(childCount()).isEqualTo(4);
    assertThat(stampedRootCount()).isEqualTo(1);
    assertThat(contractProducts()).containsExactly("HEALTH", "LEGAL");

    ProductRuleAck ack = productRuleService.deleteProductRule(ruleId, false);

    assertThat(ack.revert()).isNotNull();
    assertThat(ack.revert().childrenRemoved()).isEqualTo(4);
    assertThat(ack.revert().stampsCleared()).isEqualTo(1);
    assertThat(ack.revert().autoContractsDeleted()).isEqualTo(2);
    assertThat(childCount()).isZero();
    assertThat(stampedRootCount()).isZero();
    assertThat(contractProducts()).isEmpty();
    assertThat(recurringCountForProductContracts()).isZero();
    assertThat(db.fetchCount(org.jooq.impl.DSL.table("product_rules"))).isZero();
  }

  /** A human confirmation is never destroyed by a tool call; it is reported instead. */
  @Test
  void deleteKeepsConfirmedProductContractsAndNamesThemInTheAck() {
    seedBundledMandate();
    settleSubstrate();
    long ruleId = productRuleService.createProductRule(CREDITOR, PATTERN, null, false).ruleId();
    long healthContract = contractIdOf("HEALTH");
    confirmContract(healthContract);

    ProductRuleAck ack = productRuleService.deleteProductRule(ruleId, false);

    assertThat(contractProducts()).containsExactly("HEALTH");
    assertThat(ack.revert().autoContractsDeleted()).isEqualTo(1);
    assertThat(ack.revert().keptConfirmedContracts())
        .hasSize(1)
        .allSatisfy(entry -> assertThat(entry).contains("HEALTH").contains(MANDATE));
    assertThat(ack.message()).containsIgnoringCase("kept");
  }

  /**
   * The rollout leaves the mandate-level contract {@code ended} (spec §8 step 5). Deleting the rule
   * sends every booking back into that group, but {@code UPSERT_CONTRACTS}' {@code ON CONFLICT DO
   * NOTHING} never reopens it -- and with the rule row gone the counter surface is gone too, so the
   * creditor would vanish from the register, the review queue and the unmatched list at once.
   */
  @Test
  void deleteReopensEndedMandateContract() {
    seedBundledMandate();
    settleSubstrate();
    long mandateContract = contractIdOfMandateLevel();
    confirmContract(mandateContract);
    writeTools.endContract(mandateContract, LocalDate.of(2026, 3, 1), "superseded by products");
    assertThat(contractStatus(mandateContract)).isEqualTo("ended");
    long ruleId = productRuleService.createProductRule(CREDITOR, PATTERN, null, false).ruleId();

    ProductRuleAck ack = productRuleService.deleteProductRule(ruleId, false);

    assertThat(contractStatus(mandateContract))
        .withFailMessage(
            "the mandate-level contract is still 'ended' after the rule was deleted: every booking"
                + " is back in its group but no surface shows the creditor any more")
        .isEqualTo("open");
    assertThat(contractEndDate(mandateContract)).isNull();
    assertThat(ack.revert().mandateContractsReopened()).isEqualTo(1);
  }

  @Test
  void deleteDryRunWritesNothing() {
    seedBundledMandate();
    settleSubstrate();
    long ruleId = productRuleService.createProductRule(CREDITOR, PATTERN, null, false).ruleId();

    ProductRuleAck ack = productRuleService.deleteProductRule(ruleId, true);

    assertThat(ack.dryRun()).isTrue();
    assertThat(ack.revert().childrenRemoved()).isEqualTo(4);
    assertThat(ack.revert().autoContractsDeleted()).isEqualTo(2);
    assertThat(childCount()).isEqualTo(4);
    assertThat(contractProducts()).containsExactly("HEALTH", "LEGAL");
    assertThat(db.fetchCount(org.jooq.impl.DSL.table("product_rules"))).isEqualTo(1);
  }

  // -----------------------------------------------------------------------------------------
  // list + validation
  // -----------------------------------------------------------------------------------------

  @Test
  void listProductRulesReturnsTheResolverCounters() {
    seedBundledMandate();
    seedBooking("root-mismatch", LocalDate.of(2026, 3, 15), "150.00", MISMATCH, MANDATE);
    settleSubstrate();
    productRuleService.createProductRule(CREDITOR, PATTERN, "note", false);

    List<ProductRuleView> rules = productRuleService.listProductRules();

    assertThat(rules).hasSize(1);
    ProductRuleView rule = rules.get(0);
    assertThat(rule.creditorId()).isEqualTo(CREDITOR);
    assertThat(rule.enabled()).isTrue();
    assertThat(rule.notes()).isEqualTo("note");
    assertThat(rule.rootsVisited()).isEqualTo(3);
    assertThat(rule.rootsSplit()).isEqualTo(2);
    assertThat(rule.rootsMismatched()).isEqualTo(1);
    assertThat(rule.lastResolvedAt()).isNotNull();
  }

  @Test
  void invalidPatternIsRejected() {
    seedBundledMandate();
    settleSubstrate();

    assertThatThrownBy(() -> productRuleService.createProductRule(CREDITOR, "(?<product>[", null, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not compile");

    assertThatThrownBy(
            () -> productRuleService.createProductRule(CREDITOR, "(?<product>\\p{L}+)", null, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amount");

    assertThatThrownBy(
            () -> productRuleService.createProductRule("CDTR-UNKNOWN", PATTERN, null, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CDTR-UNKNOWN");

    assertThat(db.fetchCount(org.jooq.impl.DSL.table("product_rules"))).isZero();
  }

  @Test
  void unknownRuleIdIsRejected() {
    assertThatThrownBy(() -> productRuleService.setProductRuleEnabled(999L, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("999");
    assertThatThrownBy(() -> productRuleService.deleteProductRule(999L, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("999");
    assertThatThrownBy(() -> productRuleService.updateProductRule(999L, PATTERN, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("999");
  }

  @Test
  void secondRuleForTheSameCreditorIsRejected() {
    seedBundledMandate();
    settleSubstrate();
    productRuleService.createProductRule(CREDITOR, PATTERN, null, false);

    assertThatThrownBy(() -> productRuleService.createProductRule(CREDITOR, PATTERN, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CREDITOR);
  }

  // -----------------------------------------------------------------------------------------
  // fixtures and helpers
  // -----------------------------------------------------------------------------------------

  /** Two months of the bundled mandate, so the contract layer can derive a series. */
  private void seedBundledMandate() {
    seedBooking("root-jan", LocalDate.of(2026, 1, 15), "150.00", MULTI, MANDATE);
    seedBooking("root-feb", LocalDate.of(2026, 2, 15), "150.00", MULTI, MANDATE);
  }

  /** A single-position booking on a second mandate: the stamp case. */
  private void seedStampedBooking() {
    seedBooking("root-stamp", LocalDate.of(2026, 1, 20), "90.00", SINGLE, OTHER_MANDATE);
  }

  private void seedBooking(
      String hash, LocalDate date, String amount, String remittance, String mandate) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, mandate_id, raw)"
            + " VALUES (?, 0, ?, ?, ?, 'EUR', 'DBIT', 'BOOK', ?, 'SYNTHETIC INSURER', ?, ?,"
            + " '{}'::jsonb)",
        hash,
        importId,
        date,
        new BigDecimal(amount),
        remittance,
        CREDITOR,
        mandate);
  }

  /** The state a rule is authored against: counterparties and mandate-level contracts exist. */
  private void settleSubstrate() {
    counterpartyResolver.resolve();
    contractResolver.resolve();
  }

  private long seedDisabledRule() {
    return db.fetchOne(
            "INSERT INTO product_rules (creditor_id, position_pattern, enabled)"
                + " VALUES (?, ?, false) RETURNING id",
            CREDITOR,
            PATTERN)
        .get("id", Long.class);
  }

  private void confirmContract(long contractId) {
    db.execute(
        "UPDATE contracts SET status = 'confirmed', source = 'confirmed', confirmed_at = now()"
            + " WHERE id = ?",
        contractId);
  }

  private int childCount() {
    return db.fetchCount(
        org.jooq.impl.DSL.table("transactions"),
        org.jooq.impl.DSL.condition("split_parent_content_hash is not null"));
  }

  private int stampedRootCount() {
    return db.fetchCount(
        org.jooq.impl.DSL.table("transactions"),
        org.jooq.impl.DSL.condition("split_parent_content_hash is null and product is not null"));
  }

  private List<String> childProducts() {
    return db.fetch(
            "SELECT product FROM transactions WHERE split_parent_content_hash IS NOT NULL"
                + " ORDER BY product, content_hash")
        .map(r -> r.get("product", String.class));
  }

  private List<String> policyNumbers() {
    return db.fetch(
            "SELECT product_policy_no FROM transactions"
                + " WHERE split_parent_content_hash IS NOT NULL ORDER BY product, content_hash")
        .map(r -> r.get("product_policy_no", String.class));
  }

  private List<String> contractProducts() {
    return db.fetch("SELECT product FROM contracts WHERE product IS NOT NULL ORDER BY product")
        .map(r -> r.get("product", String.class));
  }

  private List<Long> contractIds() {
    return db.fetch("SELECT id FROM contracts WHERE product IS NOT NULL ORDER BY product")
        .map(r -> r.get("id", Long.class));
  }

  private long contractIdOf(String product) {
    return db.fetchOne("SELECT id FROM contracts WHERE product = ?", product).get("id", Long.class);
  }

  private long contractIdOfMandateLevel() {
    return db.fetchOne(
            "SELECT id FROM contracts WHERE product IS NULL AND mandate_id = ?", MANDATE)
        .get("id", Long.class);
  }

  private String contractStatus(long contractId) {
    return db.fetchOne("SELECT status FROM contracts WHERE id = ?", contractId)
        .get("status", String.class);
  }

  private LocalDate contractEndDate(long contractId) {
    return db.fetchOne("SELECT end_date FROM contracts WHERE id = ?", contractId)
        .get("end_date", LocalDate.class);
  }

  private int recurringCountForProductContracts() {
    return db.fetchOne(
            "SELECT count(*) AS c FROM recurring r JOIN contracts c ON c.id = r.contract_id"
                + " WHERE c.product IS NOT NULL")
        .get("c", Integer.class);
  }

  private String rulePattern(long ruleId) {
    return db.fetchOne("SELECT position_pattern FROM product_rules WHERE id = ?", ruleId)
        .get("position_pattern", String.class);
  }

  private String ruleNotes(long ruleId) {
    return db.fetchOne("SELECT notes FROM product_rules WHERE id = ?", ruleId)
        .get("notes", String.class);
  }
}
