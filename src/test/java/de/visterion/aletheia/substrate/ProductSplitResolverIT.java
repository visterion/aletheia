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
 * #productNamesAreNormalizedByPostgresNotByJava} is the engine-parity tripwire — it only means
 * anything when the folding runs against the same PostgreSQL/libc pair production uses. {@link
 * #capitalisationVariantsFoldToOneProduct} covers the creditor's mid-history capitalisation change
 * but is <em>not</em> a tripwire on its own: its fixtures are pure ASCII, and {@code Health}/{@code
 * HEALTH} fold identically under Java's {@code toUpperCase} too.
 *
 * <p>All fixtures are hand-invented: {@code CDTR-INSURER}, {@code SYNTHETIC INSURER}, {@code
 * POLICY-1}, products {@code Health}/{@code Legal}/{@code Travel}/{@code Straße}. No production
 * creditor id, mandate reference or remittance string exists in this repository.
 */
class ProductSplitResolverIT extends AbstractPostgresIT {

  private static final String CREDITOR = "CDTR-INSURER";
  private static final String MANDATE = "POLICY-1";

  /**
   * U+2003, spelled as an escape rather than inlined: it is invisible in an editor, and the whole
   * point of {@link #productNamesAreNormalizedByPostgresNotByJava} is that it is there.
   */
  private static final String EM_SPACE = String.valueOf((char) 0x2003);

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

  /**
   * Admits an EM SPACE (U+2003) <em>inside</em> the product name. Java's {@code \s} does not match
   * it, so it has to be spelled into the product class explicitly; PostgreSQL's {@code \s} collapses
   * it, which is precisely the divergence {@link #productNamesAreNormalizedByPostgresNotByJava}
   * exists to pin.
   */
  private static final String PATTERN_UNICODE =
      "(?<product>[\\p{L}\\x{2003}]+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?"
          + "(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** The N side of the N&harr;1 lifecycle fixture: matches {@code Legal} + {@code Travel}. */
  private static final String PATTERN_TWO_POSITIONS =
      "(?<product>Legal|Travel)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?"
          + "(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** The 1 side of the same fixture: matches {@code Health} alone, for the full booking amount. */
  private static final String PATTERN_ONE_POSITION =
      "(?<product>Health)\\s+(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /**
   * One remittance that parses to either one or two positions depending on the rule, each summing
   * exactly to the booking amount of {@code 150.00}. That is what makes both lifecycle transitions
   * testable on the same booking, each starting from the state the other ends in.
   */
  private static final String LIFECYCLE_REMITTANCE =
      "POLICY-1 Health 150,00 Legal 100,00 Travel 50,00";

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

  /**
   * The no-epsilon guard at cent level: {@code 99,99 + 50,00 = 149,99} against a booking of {@code
   * 150,00}. The other mismatch fixtures are off by 10.00 and 849.00, which a &plusmn;0.01 tolerance
   * or a "round the remainder into the largest position" fix would still reject — so they do not
   * discriminate. Spec §5: no tolerance, no epsilon, no rounding of a remainder.
   */
  @Test
  void centLevelNearMissIsAMismatchNotARounding() {
    seedRule(PATTERN);
    String parent = seedBooking("nearmiss", "150.00", "POLICY-1 Health 99,99 Legal 50,00");

    resolver.resolve();

    assertThat(children(parent)).isEmpty();
    assertThat(product(parent)).isNull();
    assertThat(counter("roots_mismatched")).isEqualTo(1);
    assertThat(counter("roots_split")).isZero();
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

  /**
   * The engine-parity tripwire proper: it fails the moment product normalization moves into Java.
   *
   * <p>Both fixtures are chosen because the two engines genuinely disagree about them, which pure
   * ASCII never does:
   *
   * <ul>
   *   <li>{@code Straße} — PostgreSQL's {@code upper()} delegates to libc, and this project pins
   *       {@code postgres:16-alpine} (musl), where {@code upper('straße')} is {@code STRAẞE}. Java's
   *       {@code toUpperCase(Locale.ROOT)} has no {@code ß -> ẞ} mapping and yields {@code STRASSE}
   *       — which is itself a fixpoint of the V19 CHECK, so a Java normal form would pass the
   *       constraint and quietly mint a <em>second</em> product for the same thing. That is the V18
   *       failure mode one level down, and no constraint would catch it.
   *   <li>{@code Travel}+U+2003+{@code Plus} — PostgreSQL's {@code \s} collapses the EM SPACE to a
   *       single {@code U+0020}; Java's {@code \s} and {@code String.trim()} do not touch it. A
   *       Java-normalized value would not be a fixpoint and would violate the V19 CHECK outright.
   * </ul>
   *
   * <p>Environment dependency, stated honestly: the {@code STRAẞE} expectation encodes <b>musl</b>
   * behaviour. A glibc-based Postgres yields {@code STRAßE}. Like {@code
   * NameNormalizationSqlIT#engineTripwireUpperOfEszettIsCapitalEszettUnderMusl}, this asserts the
   * production engine, so a red here means the image pin moved — fix the pin, do not adjust the
   * expectation.
   */
  @Test
  void productNamesAreNormalizedByPostgresNotByJava() {
    seedRule(PATTERN_UNICODE);
    String parent =
        seedBooking(
            "engine", "150.00", "POLICY-1 Straße 100,00 Travel" + EM_SPACE + "Plus 50,00");
    String eszett = seedBooking("eszett", "100.00", "POLICY-1 Straße 100,00");

    resolver.resolve();

    // The eszett case on its own root, because it is the insidious one: STRASSE is itself a
    // fixpoint of the V19 CHECK, so a Java normal form would be accepted here and would mint a
    // second product for the same thing. No constraint catches that -- only this assertion does.
    assertThat(product(eszett))
        .withFailMessage(
            "product was '%s', not 'STRAẞE'. PostgreSQL on musl maps ß to capital eszett; Java's"
                + " toUpperCase yields STRASSE, which passes the V19 CHECK and would silently"
                + " become a second product for the same cover.",
            product(eszett))
        .isEqualTo("STRAẞE");

    Result<Record> children = children(parent);
    assertThat(children).hasSize(2);
    assertThat(sumOf(children)).isEqualByComparingTo("150.00");
    assertThat(children.map(r -> r.get("product", String.class)))
        .withFailMessage(
            "Products must carry the PostgreSQL normal form. Java's toUpperCase would produce"
                + " STRASSE (no ss -> capital eszett mapping) and would leave the EM SPACE in"
                + " TRAVEL PLUS uncollapsed. Getting anything but [STRAẞE, TRAVEL PLUS] here means"
                + " either normalization moved into Java, or the postgres:16-alpine image is no"
                + " longer musl-based.")
        .containsExactlyInAnyOrder("STRAẞE", "TRAVEL PLUS");
    // The values reached the column, so they satisfy V19's fixpoint CHECK by construction; the
    // Java forms would not (EM SPACE) or would collide later (STRASSE).
  }

  // -----------------------------------------------------------------------------------------
  // idempotency and rule lifecycle
  // -----------------------------------------------------------------------------------------

  /**
   * Re-running must write <em>nothing</em>, not merely end in the same shape.
   *
   * <p>Row counts and child hashes alone do not prove that: a delete-and-recreate reproduces both
   * exactly, because the child key is derived from the parent hash and the index. The identity
   * column is the only value that changes under a recreate, so it is what pins the "re-delete and
   * recreate on every resolve, forever" failure mode.
   */
  @Test
  void rerunCreatesNothing() {
    seedRule(PATTERN);
    String parent = seedBooking("rerun", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");

    resolver.resolve();
    List<String> firstHashes = childHashes(parent);
    List<Long> firstIds = childIds(parent);
    resolver.resolve();

    assertThat(firstHashes).hasSize(2);
    assertThat(childHashes(parent)).isEqualTo(firstHashes);
    assertThat(childIds(parent))
        .withFailMessage(
            "child ids changed from %s to %s: the children were deleted and recreated, which is a"
                + " write on every resolve even though the result looks identical",
            firstIds, childIds(parent))
        .isEqualTo(firstIds);
    assertThat(db.fetchCount(DSL.table("transactions"))).isEqualTo(3);
  }

  /**
   * N&rarr;1: a rule edit that turns a split booking into a single position must delete the product
   * children <b>and</b> stamp the root. Leaving the children behind would double-count at the
   * contract grain — orphan children summing to the full amount plus a stamped parent.
   */
  @Test
  void ruleEditFromSplitToSinglePositionDeletesChildrenAndStampsRoot() {
    seedRule(PATTERN_TWO_POSITIONS);
    String parent = seedBooking("n-to-1", "150.00", LIFECYCLE_REMITTANCE);
    resolver.resolve();
    assertThat(children(parent)).hasSize(2);
    assertThat(product(parent)).isNull();

    setRulePattern(PATTERN_ONE_POSITION);
    resolver.resolve();

    assertThat(children(parent)).isEmpty();
    assertThat(product(parent)).isEqualTo("HEALTH");
    assertThat(db.fetchCount(DSL.table("transactions"))).isEqualTo(1);
  }

  /**
   * 1&rarr;N: the mirror image. Children are written and the root's stamp is cleared — starting
   * from a root that really was stamped, so the {@code product IS NULL} assertion is not trivially
   * true the way it is on a parent that never carried one.
   */
  @Test
  void ruleEditFromSinglePositionToSplitClearsRootStamp() {
    seedRule(PATTERN_ONE_POSITION);
    String parent = seedBooking("1-to-n", "150.00", LIFECYCLE_REMITTANCE);
    resolver.resolve();
    assertThat(product(parent)).isEqualTo("HEALTH");
    assertThat(children(parent)).isEmpty();

    setRulePattern(PATTERN_TWO_POSITIONS);
    resolver.resolve();

    Result<Record> children = children(parent);
    assertThat(children).hasSize(2);
    assertThat(children.map(r -> r.get("product", String.class)))
        .containsExactly("LEGAL", "TRAVEL");
    assertThat(sumOf(children)).isEqualByComparingTo("150.00");
    assertThat(product(parent)).isNull();
    assertThat(policyNo(parent)).isNull();
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

  /**
   * Per-root error isolation. One root that throws must not abort the creditor's remaining history,
   * and must not cost the counter surface its update — otherwise {@code list_product_rules} and the
   * {@code wake_up} warning line would keep showing the previous pass's numbers exactly when
   * something is wrong.
   *
   * <p>The failure is provoked the way production can produce it: another row already occupies the
   * natural key {@code (content_hash, occurrence_index)} that the poisoned root's first child would
   * claim — the collision a concurrent {@code split_transaction} can cause. The blocker belongs to
   * a different creditor, so it is never itself a root of this rule.
   */
  @Test
  void failingRootDoesNotAbortTheRestOrTheCounterWrite() {
    seedRule(PATTERN);
    // Roots are processed ordered by content_hash, so "root-a-..." is visited before "root-b-...":
    // the healthy root really comes after the poisoned one.
    String poisoned = seedBooking("a-poison", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    String healthy = seedBooking("b-healthy", "150.00", "POLICY-1 Health 100,00 Legal SUB-2 50,00");
    seedNaturalKeyBlocker(SplitChildWriter.syntheticSplitHash(poisoned, 0));

    resolver.resolve();

    assertThat(children(poisoned)).isEmpty();
    assertThat(product(poisoned)).isNull();
    assertThat(children(healthy)).hasSize(2);
    assertThat(sumOf(children(healthy))).isEqualByComparingTo("150.00");
    assertThat(counter("roots_visited")).isEqualTo(2);
    assertThat(counter("roots_split")).isEqualTo(1);
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

  /**
   * An unrelated raw booking occupying {@code contentHash} at {@code occurrence_index = 0}, so the
   * child insert that wants that key fails on {@code uq_transactions_natural_key}. It carries
   * another creditor id and therefore never becomes a root of the rule under test.
   */
  private void seedNaturalKeyBlocker(String contentHash) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, raw)"
            + " VALUES (?, 0, ?, ?, '1.00', 'EUR', 'DBIT', 'BOOK', 'blocker', 'SYNTHETIC OTHER',"
            + " 'CDTR-OTHER', '{}'::jsonb)",
        contentHash,
        importId,
        LocalDate.of(2026, 1, 15));
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

  /** The identity column: unchanged means "not written", where a hash only means "same shape". */
  private List<Long> childIds(String parentHash) {
    return children(parentHash).map(r -> r.get("id", Long.class));
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
