package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.mcp.Allocation;
import de.visterion.aletheia.mcp.TxReference;
import de.visterion.aletheia.mcp.WriteTools;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ProductSplitResolver} end to end, in the container.
 *
 * <p>This suite must be an IT, not a plain unit test: product identity is the SQL normal form, and
 * Java cannot reproduce it (see {@link NameNormalization}). {@link
 * #capitalisationVariantsFoldToOneProduct} is the engine-parity tripwire — it only means anything
 * when the folding runs against the same PostgreSQL/libc pair production uses.
 *
 * <p>All fixtures are hand-invented: {@code CDTR-INSURER}, {@code SYNTHETIC INSURER}, {@code
 * POLICY-1}, products {@code Health}/{@code Legal}/{@code Travel}. No production creditor id,
 * mandate reference or remittance string exists in this repository.
 */
class ProductSplitResolverIT extends AbstractPostgresIT {

  private static final String CREDITOR = "CDTR-INSURER";
  private static final String MANDATE = "POLICY-1";

  /**
   * The rule pattern of the spec's synthetic illustration. The policy class must admit letters and
   * dashes; {@code [0-9.]+} cannot match {@code SUB-2} and would silently drop the second position.
   */
  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Cosmetically different, capture-identical: a rule edit that must not duplicate anything. */
  private static final String PATTERN_EQUIVALENT =
      "(?<product>[\\p{L}]+)\\s+(?:(?<policy>[A-Z0-9.\\-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Same positions, but the policy group captures only the alphabetic prefix ({@code SUB}). */
  private static final String PATTERN_SHORT_POLICY =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z]+)-[0-9]+\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Matches nothing in these fixtures: drives the "rule edited to zero positions" lifecycle. */
  private static final String PATTERN_NO_MATCH =
      "(?<product>ZZZZZ)\\s+(?<amount>[0-9]+,[0-9]{2})";

  @Autowired DSLContext db;
  @Autowired ProductSplitResolver resolver;
  @Autowired WriteTools writeTools;

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
            + " counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE product_rules RESTART IDENTITY CASCADE");
  }

  // -----------------------------------------------------------------------------------------
  // stamp / split decision
  // -----------------------------------------------------------------------------------------

  @Test
  void multiPositionBookingIsSplitIntoChildren() {
    seedRule(PATTERN);
    String parent =
        seedBooking("multi", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");

    resolver.resolve();

    Result<Record> children = children(parent);
    assertThat(children).hasSize(2);
    assertThat(children.map(r -> r.get("product", String.class)))
        .containsExactly("HEALTH", "LEGAL");
    assertThat(children.map(r -> r.get("product_policy_no", String.class)))
        .containsExactly(null, "SUB-2");
    assertThat(sumOf(children)).isEqualByComparingTo("150.00");
    // Children inherit the creditor identity, otherwise v_contract_evidence would not see them.
    assertThat(children.map(r -> r.get("creditor_id", String.class)))
        .containsOnly(CREDITOR);
    assertThat(children.map(r -> r.get("mandate_id", String.class))).containsOnly(MANDATE);
    // The split parent carries no stamp of its own.
    assertThat(product(parent)).isNull();
  }

  @Test
  void singlePositionBookingStampsRootWithoutSplitting() {
    seedRule(PATTERN);
    String parent = seedBooking("single", "100.00", "POLICY-1 Health 100,00");

    resolver.resolve();

    assertThat(children(parent)).isEmpty();
    assertThat(product(parent)).isEqualTo("HEALTH");
    assertThat(policyNo(parent)).isNull();
  }

  @Test
  void sumMismatchLeavesBookingUntouched() {
    seedRule(PATTERN);
    String parent = seedBooking("mismatch", "150.00", "POLICY-1 Health 100,00 Legal 40,00");

    resolver.resolve();

    assertThat(children(parent)).isEmpty();
    assertThat(product(parent)).isNull();
    assertThat(counter("roots_mismatched")).isEqualTo(1);
  }

  @Test
  void sameProductTwiceFoldsIntoOneChild() {
    seedRule(PATTERN);
    String parent = seedBooking("fold", "106.00", "POLICY-1 Health 100,00 Health 6,00");

    resolver.resolve();

    Result<Record> children = children(parent);
    assertThat(children).hasSize(1);
    assertThat(children.get(0).get("product", String.class)).isEqualTo("HEALTH");
    assertThat(children.get(0).get("amount", BigDecimal.class)).isEqualByComparingTo("106.00");
    // The folded remittance is the concatenation of the matched substrings, in fold order: it is
    // part of the idempotency key, so it must be deterministic.
    assertThat(children.get(0).get("remittance_info", String.class))
        .isEqualTo("Health 100,00 Health 6,00");
  }

  /**
   * The engine-parity tripwire. The creditor changed capitalisation mid-history; two spellings must
   * produce ONE product identity. Folding keys on the SQL normal form, which is why this case can
   * only exist in the container.
   */
  @Test
  void capitalisationVariantsFoldToOneProduct() {
    seedRule(PATTERN);
    String january =
        seedBooking(
            "jan", LocalDate.of(2026, 1, 15), "150.00", "POLICY-1 Health 100,00 Legal 50,00");
    String february =
        seedBooking(
            "feb", LocalDate.of(2026, 2, 15), "150.00", "POLICY-1 HEALTH 100,00 LEGAL 50,00");

    resolver.resolve();

    assertThat(children(january)).hasSize(2);
    assertThat(children(february)).hasSize(2);
    List<String> distinct =
        db.fetch(
                "SELECT DISTINCT product FROM transactions WHERE product IS NOT NULL"
                    + " ORDER BY product")
            .map(r -> r.get("product", String.class));
    assertThat(distinct).containsExactly("HEALTH", "LEGAL");
  }

  // -----------------------------------------------------------------------------------------
  // idempotency and rule lifecycle
  // -----------------------------------------------------------------------------------------

  @Test
  void rerunCreatesNothing() {
    seedRule(PATTERN);
    String parent = seedBooking("rerun", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");

    resolver.resolve();
    List<String> first = childHashes(parent);
    resolver.resolve();
    List<String> second = childHashes(parent);

    assertThat(first).hasSize(2);
    assertThat(second).isEqualTo(first);
    assertThat(db.fetchCount(DSL.table("transactions"))).isEqualTo(3);
  }

  @Test
  void ruleEditReplacesChildrenRatherThanDuplicating() {
    seedRule(PATTERN);
    String parent = seedBooking("edit", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    resolver.resolve();

    setRulePattern(PATTERN_EQUIVALENT);
    resolver.resolve();

    assertThat(children(parent)).hasSize(2);
  }

  @Test
  void changedPolicyNumberRefreshes() {
    seedRule(PATTERN);
    String parent = seedBooking("policy", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    resolver.resolve();
    assertThat(policyNumbers(parent)).containsExactly(null, "SUB-2");

    setRulePattern(PATTERN_SHORT_POLICY);
    resolver.resolve();

    assertThat(children(parent)).hasSize(2);
    assertThat(policyNumbers(parent)).containsExactly(null, "SUB");
  }

  @Test
  void editToZeroPositionsRemovesChildrenAndClearsStamp() {
    seedRule(PATTERN);
    String split = seedBooking("split", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    String stamped = seedBooking("stamped", "100.00", "POLICY-1 Health 100,00");
    resolver.resolve();
    assertThat(children(split)).hasSize(2);
    assertThat(product(stamped)).isEqualTo("HEALTH");

    setRulePattern(PATTERN_NO_MATCH);
    resolver.resolve();

    assertThat(children(split)).isEmpty();
    assertThat(product(split)).isNull();
    assertThat(product(stamped)).isNull();
  }

  @Test
  void disabledRuleLeavesChildrenAndStampsInPlace() {
    seedRule(PATTERN);
    String split = seedBooking("split", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    String stamped = seedBooking("stamped", "100.00", "POLICY-1 Health 100,00");
    resolver.resolve();

    // Disable is a pause, not a revert -- even together with a pattern that now matches nothing.
    db.execute("UPDATE product_rules SET enabled = false, position_pattern = ?", PATTERN_NO_MATCH);
    resolver.resolve();

    assertThat(children(split)).hasSize(2);
    assertThat(product(stamped)).isEqualTo("HEALTH");
  }

  // -----------------------------------------------------------------------------------------
  // skips
  // -----------------------------------------------------------------------------------------

  /**
   * A human {@code split_transaction} outranks the rule. Without the stamp-clearing half of the
   * skip, the stamped parent would keep feeding the product contract the full pre-split amount
   * while the human children feed the NULL group.
   */
  @Test
  void humanSplitChildMakesResolverSkipAndClearStamp() {
    seedRule(PATTERN);
    String parent = seedBooking("human", "100.00", "POLICY-1 Health 100,00");
    resolver.resolve();
    assertThat(product(parent)).isEqualTo("HEALTH");

    writeTools.splitTransaction(
        new TxReference(parent, 0),
        List.of(
            new Allocation(null, "SYNTHETIC INSURER", MANDATE, new BigDecimal("60.00"), "part a"),
            new Allocation(null, "Bargeld", null, new BigDecimal("40.00"), "part b")),
        null);

    resolver.resolve();

    Result<Record> children = children(parent);
    assertThat(children).hasSize(2);
    assertThat(children.map(r -> r.get("product", String.class))).containsOnlyNulls();
    assertThat(sumOf(children)).isEqualByComparingTo("100.00");
    assertThat(product(parent)).isNull();
  }

  /**
   * A {@code reattribute_transaction} stamp is a human decision. Splitting the root would hide it
   * from every evidence view and erase the attributed identity without an error.
   */
  @Test
  void attributedRootIsSkipped() {
    seedRule(PATTERN);
    String parent =
        seedBooking(
            "attributed",
            LocalDate.of(2026, 1, 15),
            "150.00",
            "POLICY-1 Health 100,00 Legal SUB-2 50,00",
            "SYNTHETIC MERCHANT");

    resolver.resolve();

    assertThat(children(parent)).isEmpty();
    assertThat(product(parent)).isNull();
  }

  // -----------------------------------------------------------------------------------------
  // counters
  // -----------------------------------------------------------------------------------------

  @Test
  void countersAreRecorded() {
    seedRule(PATTERN);
    seedBooking("split", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    seedBooking("stamped", "100.00", "POLICY-1 Health 100,00");
    seedBooking("mismatch", "150.00", "POLICY-1 Health 100,00 Legal 40,00");

    resolver.resolve();

    assertThat(counter("roots_visited")).isEqualTo(3);
    assertThat(counter("roots_split")).isEqualTo(1);
    assertThat(counter("roots_stamped")).isEqualTo(1);
    assertThat(counter("roots_mismatched")).isEqualTo(1);
    assertThat(db.fetchOne("SELECT last_resolved_at FROM product_rules").get(0)).isNotNull();
  }

  /** A creditor without a rule is never visited: the change is scoped, not global. */
  @Test
  void creditorWithoutARuleIsUntouched() {
    seedRule(PATTERN);
    String other =
        seedBooking(
            "other",
            LocalDate.of(2026, 1, 15),
            "150.00",
            "POLICY-1 Health 100,00 Legal SUB-2 50,00",
            null,
            "CDTR-OTHER");

    resolver.resolve();

    assertThat(children(other)).isEmpty();
    assertThat(product(other)).isNull();
    assertThat(counter("roots_visited")).isZero();
  }

  // -----------------------------------------------------------------------------------------
  // fixtures and helpers
  // -----------------------------------------------------------------------------------------

  private void seedRule(String pattern) {
    db.execute(
        "INSERT INTO product_rules (creditor_id, position_pattern) VALUES (?, ?)",
        CREDITOR,
        pattern);
  }

  private void setRulePattern(String pattern) {
    db.execute("UPDATE product_rules SET position_pattern = ? WHERE creditor_id = ?", pattern,
        CREDITOR);
  }

  private String seedBooking(String tag, String amount, String remittance) {
    return seedBooking(tag, LocalDate.of(2026, 1, 15), amount, remittance, null, CREDITOR);
  }

  private String seedBooking(String tag, LocalDate date, String amount, String remittance) {
    return seedBooking(tag, date, amount, remittance, null, CREDITOR);
  }

  private String seedBooking(
      String tag, LocalDate date, String amount, String remittance, String attributedName) {
    return seedBooking(tag, date, amount, remittance, attributedName, CREDITOR);
  }

  private String seedBooking(
      String tag,
      LocalDate date,
      String amount,
      String remittance,
      String attributedName,
      String creditorId) {
    String hash = "root-" + tag;
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, mandate_id, attributed_name, attribution_source, raw)"
            + " VALUES (?, 0, ?, ?, ?, 'EUR', 'DBIT', 'BOOK', ?, 'SYNTHETIC INSURER', ?, ?, ?, ?,"
            + " '{}'::jsonb)",
        hash,
        importId,
        date,
        new BigDecimal(amount),
        remittance,
        creditorId,
        MANDATE,
        attributedName,
        attributedName == null ? null : "manual");
    return hash;
  }

  private Result<Record> children(String parentHash) {
    return db.fetch(
        "SELECT * FROM transactions WHERE split_parent_content_hash = ?"
            + " AND split_parent_occurrence_index = 0 ORDER BY product, content_hash",
        parentHash);
  }

  private List<String> childHashes(String parentHash) {
    return children(parentHash).map(r -> r.get("content_hash", String.class));
  }

  private List<String> policyNumbers(String parentHash) {
    return children(parentHash).map(r -> r.get("product_policy_no", String.class));
  }

  private BigDecimal sumOf(Result<Record> rows) {
    return rows.stream()
        .map(r -> r.get("amount", BigDecimal.class))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private String product(String contentHash) {
    return db.fetchOne("SELECT product FROM transactions WHERE content_hash = ?", contentHash)
        .get("product", String.class);
  }

  private String policyNo(String contentHash) {
    return db.fetchOne(
            "SELECT product_policy_no FROM transactions WHERE content_hash = ?", contentHash)
        .get("product_policy_no", String.class);
  }

  private int counter(String column) {
    return db.fetchOne("SELECT " + column + " AS c FROM product_rules WHERE creditor_id = ?",
            CREDITOR)
        .get("c", Integer.class);
  }
}
