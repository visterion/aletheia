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
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ContractResolver} at {@code (counterparty_id, mandate_id, product)} grain (spec §6).
 *
 * <p>Two cases here are regression proofs rather than feature tests, and both were found on paper
 * before implementation:
 *
 * <ul>
 *   <li>{@link #productOnlyInsideMultiProductBookingsAcrossTwoMonthsGetsContract} -- the product of a
 *       bundled booking lives only on the split <em>children</em>, so grouping raw roots by product
 *       would derive nothing at all for the production case this slice exists for.
 *   <li>{@link #mandateWithoutRuleButWithHumanSplitKeepsIdenticalMonthsAndRecurring} -- the obvious
 *       repair (admit logical leaves instead of raw roots) silently drops the parent of an
 *       <em>ordinary</em> human split: its children carry {@code product IS NULL}, so the month
 *       vanishes from both upserts and {@code UPSERT_RECURRING}'s {@code DO UPDATE} rewrites even a
 *       confirmed contract's measured columns down to the residue, on every ingest, forever, with
 *       no error. Its fixture splits onto a <b>name-based</b> allocation on purpose: children that
 *       stayed on the creditor identity would still carry the mandate and would not discriminate
 *       against that wrong predicate.
 * </ul>
 *
 * <p>All fixtures are hand-invented: {@code CDTR-INSURER}, {@code SYNTHETIC INSURER}, {@code
 * POLICY-1}, products {@code Health}/{@code Legal}. No production creditor id, mandate reference or
 * remittance string exists in this repository.
 */
class ContractResolverProductIT extends AbstractPostgresIT {

  private static final String CREDITOR = "CDTR-INSURER";
  private static final String OTHER_CREDITOR = "CDTR-OTHER";
  private static final String MANDATE = "POLICY-1";

  /** The spec's synthetic illustration; identical to the one {@code ProductSplitResolverIT} uses. */
  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Two positions summing to 150.00: the bundled-mandate case. */
  private static final String MULTI = "POLICY-1 Health 100,00 Legal 50,00";

  /** One position for the whole booking: the stamped-root case. */
  private static final String SINGLE = "POLICY-1 Health 150,00";

  /** Parses to nothing under {@link #PATTERN}: the unmatched residue. */
  private static final String UNPARSED = "PREMIUM ADJUSTMENT";

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ProductSplitResolver productSplitResolver;
  @Autowired ContractResolver contractResolver;
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
            + " counterparty_alias, counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE product_rules RESTART IDENTITY CASCADE");
  }

  // -----------------------------------------------------------------------------------------
  // P1: the two regression proofs
  // -----------------------------------------------------------------------------------------

  @Test
  void productOnlyInsideMultiProductBookingsAcrossTwoMonthsGetsContract() {
    seedRule(PATTERN);
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);

    settle();

    assertThat(contractProducts()).containsExactlyInAnyOrder("HEALTH", "LEGAL");
    // The lump is gone: the parents were superseded by product children, so no NULL-product
    // contract exists for a fully parsed mandate.
    assertThat(contractProducts()).doesNotContainNull();
  }

  /**
   * The round-2 Critical. A mandate with no product rule must behave <b>byte-identically</b> to
   * today, human splits included: no row can carry a product, so the new predicate degenerates to
   * plain raw-root semantics.
   */
  @Test
  void mandateWithoutRuleButWithHumanSplitKeepsIdenticalMonthsAndRecurring() {
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);

    settle();
    List<RecurringRow> before = recurringRows();
    assertThat(before).hasSize(1);
    assertThat(before.get(0).occurrenceCount()).isEqualTo(2);

    // A human splits the January booking onto NAME-based counterparties: the children carry
    // neither creditor_id nor mandate_id, so a leaf-based predicate would lose January entirely.
    writeTools.splitTransaction(
        new TxReference("root-jan", 0),
        List.of(
            new Allocation(null, "SYNTHETIC CASH", null, new BigDecimal("100.00"), "cash part"),
            new Allocation(null, "SYNTHETIC KIOSK", null, new BigDecimal("50.00"), "kiosk part")),
        null);

    settle();

    assertThat(recurringRows()).isEqualTo(before);
    assertThat(contractProducts()).containsExactly((String) null);
  }

  // -----------------------------------------------------------------------------------------
  // the other row classes of the predicate
  // -----------------------------------------------------------------------------------------

  /** Same scoped-exception check on the attributed (PayPal) branch, which shares the WHERE. */
  @Test
  void humanSplitAttributedRootStillDerives() {
    seedAttributedBooking("att-jan", LocalDate.of(2026, 1, 10), "30.00");
    seedAttributedBooking("att-feb", LocalDate.of(2026, 2, 10), "30.00");

    settle();
    List<RecurringRow> before = recurringRows();
    assertThat(before).hasSize(1);
    assertThat(before.get(0).occurrenceCount()).isEqualTo(2);
    assertThat(mandatesOf()).containsExactly("attributed");

    writeTools.splitTransaction(
        new TxReference("att-jan", 0),
        List.of(
            new Allocation(null, "SYNTHETIC CASH", null, new BigDecimal("20.00"), "part a"),
            new Allocation(null, "SYNTHETIC KIOSK", null, new BigDecimal("10.00"), "part b")),
        null);

    settle();

    assertThat(mandatesOf()).containsExactly("attributed");
    assertThat(recurringRows()).isEqualTo(before);
  }

  /** TP2 doctrine: purchase parts never mint a contract, even carrying creditor id and mandate. */
  @Test
  void ordinarySplitChildrenStillMintNoContract() {
    long otherCp = seedCreditorCounterparty(OTHER_CREDITOR, "SYNTHETIC OTHER");
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);

    for (String parent : List.of("root-jan", "root-feb")) {
      writeTools.splitTransaction(
          new TxReference(parent, 0),
          List.of(
              new Allocation(otherCp, null, "OTHER-MANDATE", new BigDecimal("100.00"), "part a"),
              new Allocation(otherCp, null, "OTHER-MANDATE", new BigDecimal("50.00"), "part b")),
          null);
    }

    settle();

    assertThat(
            db.fetchOne("SELECT count(*) AS n FROM contracts WHERE counterparty_id = ?", otherCp)
                .get("n", Integer.class))
        .isZero();
    // The creditor's own mandate is untouched: its roots are not product-split.
    assertThat(contractProducts()).containsExactly((String) null);
  }

  // -----------------------------------------------------------------------------------------
  // measured series
  // -----------------------------------------------------------------------------------------

  @Test
  void recurringCarriesPerProductAmountNotTheLump() {
    seedRule(PATTERN);
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);

    settle();

    List<RecurringRow> rows = recurringRows();
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(RecurringRow::product).containsExactly("HEALTH", "LEGAL");
    assertThat(rows.get(0).typicalAmount()).isEqualByComparingTo("100.00");
    assertThat(rows.get(1).typicalAmount()).isEqualByComparingTo("50.00");
    assertThat(rows).extracting(RecurringRow::occurrenceCount).containsOnly(2);
  }

  @Test
  void confirmedProductContractSurvivesResolverRerun() {
    seedRule(PATTERN);
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);
    settle();

    db.execute(
        "UPDATE contracts SET status = 'confirmed', source = 'confirmed' WHERE product = 'HEALTH'");
    db.execute(
        "UPDATE recurring SET source = 'confirmed' WHERE contract_id IN"
            + " (SELECT id FROM contracts WHERE product = 'HEALTH')");

    settle();

    Record health =
        db.fetchOne("SELECT status, source FROM contracts WHERE product = 'HEALTH'");
    assertThat(health.get("status", String.class)).isEqualTo("confirmed");
    assertThat(health.get("source", String.class)).isEqualTo("confirmed");
    assertThat(contractProducts()).containsExactlyInAnyOrder("HEALTH", "LEGAL");

    List<RecurringRow> rows = recurringRows();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).source()).isEqualTo("confirmed");
    assertThat(rows.get(0).typicalAmount()).isEqualByComparingTo("100.00");
  }

  @Test
  void resolveIsIdempotentAtProductGrain() {
    seedRule(PATTERN);
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);

    settle();
    List<RecurringRow> once = recurringRows();
    settle();

    assertThat(contractProducts()).containsExactlyInAnyOrder("HEALTH", "LEGAL");
    assertThat(recurringRows()).isEqualTo(once);
  }

  // -----------------------------------------------------------------------------------------
  // the two stated consequences (spec §6)
  // -----------------------------------------------------------------------------------------

  @Test
  void yearlyProductNeedsTwoYears() {
    seedRule(PATTERN);
    seedBooking("y1", LocalDate.of(2026, 3, 15), "150.00", SINGLE);

    settle();
    assertThat(contractProducts()).isEmpty();

    seedBooking("y2", LocalDate.of(2027, 3, 15), "150.00", SINGLE);
    settle();

    assertThat(contractProducts()).containsExactly("HEALTH");
  }

  @Test
  void nullProductContractExistsOnlyWhileUnparsedBookingsRemain() {
    seedRule(PATTERN);
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);
    seedBooking("mar", LocalDate.of(2026, 3, 15), "20.00", UNPARSED);
    seedBooking("apr", LocalDate.of(2026, 4, 15), "20.00", UNPARSED);

    settle();

    assertThat(contractProducts()).containsExactlyInAnyOrder("HEALTH", "LEGAL", null);
    RecurringRow residue =
        recurringRows().stream().filter(r -> r.product() == null).findFirst().orElseThrow();
    assertThat(residue.typicalAmount()).isEqualByComparingTo("20.00");
    assertThat(residue.occurrenceCount()).isEqualTo(2);
  }

  /**
   * A mandate that was lumped before the rule existed keeps its historical NULL-product row, and
   * that row must stop moving once every booking parses -- not be rewritten down to a residue that
   * no longer exists.
   */
  @Test
  void fullyParsedMandateNullProductRecurringStaysFrozen() {
    seedBooking("jan", LocalDate.of(2026, 1, 15), "150.00", MULTI);
    seedBooking("feb", LocalDate.of(2026, 2, 15), "150.00", MULTI);

    settle();
    RecurringRow lump = recurringRows().get(0);
    assertThat(lump.product()).isNull();
    assertThat(lump.typicalAmount()).isEqualByComparingTo("150.00");

    seedRule(PATTERN);
    settle();

    List<RecurringRow> rows = recurringRows();
    assertThat(rows).extracting(RecurringRow::product).containsExactly("HEALTH", "LEGAL", null);
    assertThat(rows.get(2)).isEqualTo(lump);
    assertThat(rows.get(0).typicalAmount()).isEqualByComparingTo("100.00");
    assertThat(rows.get(1).typicalAmount()).isEqualByComparingTo("50.00");
  }

  // -----------------------------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------------------------

  /** The production settle order: counterparties, then products, then contracts. */
  private void settle() {
    counterpartyResolver.resolve();
    productSplitResolver.resolve();
    contractResolver.resolve();
  }

  private void seedRule(String pattern) {
    db.execute(
        "INSERT INTO product_rules (creditor_id, position_pattern) VALUES (?, ?)",
        CREDITOR,
        pattern);
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

  private void seedAttributedBooking(String hash, LocalDate date, String amount) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, mandate_id, attributed_name, attribution_source, raw)"
            + " VALUES (?, 0, ?, ?, ?, 'EUR', 'DBIT', 'BOOK', 'passthrough', 'SYNTHETIC WALLET',"
            + " 'CDTR-WALLET', 'WALLET-MANDATE', 'SYNTHETIC MERCHANT', 'manual', '{}'::jsonb)",
        hash,
        importId,
        date,
        new BigDecimal(amount));
  }

  private long seedCreditorCounterparty(String creditorId, String displayName) {
    return db.fetchOne(
            "INSERT INTO counterparties (identity_type, identity_value, display_name)"
                + " VALUES ('creditor_id', ?, ?) RETURNING id",
            creditorId,
            displayName)
        .get("id", Long.class);
  }

  /** Every contract's product, NULLs included, so a missing/extra lump row is visible. */
  private List<String> contractProducts() {
    return db.fetch("SELECT product FROM contracts ORDER BY product NULLS LAST")
        .map(r -> r.get("product", String.class));
  }

  private List<String> mandatesOf() {
    return db.fetch("SELECT mandate_id FROM contracts ORDER BY mandate_id NULLS LAST")
        .map(r -> r.get("mandate_id", String.class));
  }

  private List<RecurringRow> recurringRows() {
    Result<Record> rows =
        db.fetch(
            "SELECT c.product, r.typical_amount, r.amount_min, r.amount_max, r.first_seen,"
                + " r.last_seen, r.occurrence_count, r.source, r.cadence"
                + " FROM recurring r JOIN contracts c ON c.id = r.contract_id"
                + " ORDER BY c.product NULLS LAST");
    return rows.map(
        r ->
            new RecurringRow(
                r.get("product", String.class),
                r.get("typical_amount", BigDecimal.class),
                r.get("amount_min", BigDecimal.class),
                r.get("amount_max", BigDecimal.class),
                r.get("first_seen", LocalDate.class),
                r.get("last_seen", LocalDate.class),
                r.get("occurrence_count", Integer.class),
                r.get("source", String.class),
                r.get("cadence", String.class)));
  }

  /** The measured series of one contract; compared as a whole so nothing drifts unnoticed. */
  private record RecurringRow(
      String product,
      BigDecimal typicalAmount,
      BigDecimal amountMin,
      BigDecimal amountMax,
      LocalDate firstSeen,
      LocalDate lastSeen,
      Integer occurrenceCount,
      String source,
      String cadence) {}
}
