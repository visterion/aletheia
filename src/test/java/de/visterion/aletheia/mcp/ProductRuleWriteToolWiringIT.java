package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins that {@code ProductSplitResolver} runs on the two <em>write-tool</em> triggers spec §5 names
 * next to startup and ingest: {@code reattribute_transaction} and {@code merge_counterparty}.
 *
 * <p>{@code ProductRuleIngestWiringIT} covers the ingest trigger and the startup trigger is the
 * {@code ApplicationRunner} itself, but both {@code WriteTools} call sites were unexercised:
 * deleting them left the whole suite green. They are not decorative -- both tools settle the
 * substrate under {@code SubstrateLock} and then let {@code ContractResolver} rewrite the measured
 * series in the same call, so a missing product pass means that call derives contracts from the
 * unsplit lump and the products appear silently late, at the next restart.
 *
 * <p>Each test creates the rule <b>after</b> the bookings exist, which is the production sequence
 * (a rule is authored for a creditor whose history is already imported) and makes the assertion
 * unambiguous: nothing but the tool call under test can have written the children.
 *
 * <p>Fixtures are hand-invented ({@code CDTR-INSURER}, {@code POLICY-1}, {@code Health}/{@code
 * Legal}); no production identifier exists in this repository.
 */
class ProductRuleWriteToolWiringIT extends AbstractPostgresIT {

  private static final String CREDITOR = "CDTR-INSURER";
  private static final String MANDATE = "POLICY-1";

  /** The spec's synthetic illustration; identical to the one the resolver ITs use. */
  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Two positions summing to 150.00: the bundled-mandate case. */
  private static final String MULTI = "POLICY-1 Health 100,00 Legal 50,00";

  @Autowired DSLContext db;
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
        "TRUNCATE TABLE recurring, contracts, counterparty_history, counterparty_tags,"
            + " counterparty_alias, tag_rules, product_rules, counterparties, transactions,"
            + " imports RESTART IDENTITY CASCADE");
  }

  @Test
  void reattributeTransactionSettlesProductSplitsInTheSameCall() {
    seedInsurerBookings();
    // An unrelated passthrough booking is the re-attribution target: re-attributing an insurer
    // root would be skipped by the resolver by design, and would prove nothing about the wiring.
    seedWalletBooking();
    seedRule();

    writeTools.reattributeTransaction(
        List.of(new TxReference("wallet-1", 0)), "SYNTHETIC MERCHANT");

    assertThat(childProducts()).containsExactly("HEALTH", "HEALTH", "LEGAL", "LEGAL");
    assertThat(contractProducts()).containsExactly("HEALTH", "LEGAL");
  }

  @Test
  void mergeCounterpartySettlesProductSplitsInTheSameCall() {
    seedInsurerBookings();
    seedRule();
    long target = seedNameCounterparty("SYNTHETIC ALPHA");
    long source = seedNameCounterparty("SYNTHETIC BETA");

    writeTools.mergeCounterparty(target, List.of(source), "same provider");

    assertThat(childProducts()).containsExactly("HEALTH", "HEALTH", "LEGAL", "LEGAL");
    assertThat(contractProducts()).containsExactly("HEALTH", "LEGAL");
  }

  // --- fixtures ---

  private void seedInsurerBookings() {
    seedBooking("root-jan", LocalDate.of(2026, 1, 15));
    seedBooking("root-feb", LocalDate.of(2026, 2, 15));
  }

  private void seedBooking(String hash, LocalDate date) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, mandate_id, raw)"
            + " VALUES (?, 0, ?, ?, 150.00, 'EUR', 'DBIT', 'BOOK', ?, 'SYNTHETIC INSURER', ?, ?,"
            + " '{}'::jsonb)",
        hash,
        importId,
        date,
        MULTI,
        CREDITOR,
        MANDATE);
  }

  private void seedWalletBooking() {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, import_id, booking_date,"
            + " amount, currency, direction, booking_status, remittance_info, counterparty_name,"
            + " creditor_id, mandate_id, raw)"
            + " VALUES ('wallet-1', 0, ?, DATE '2026-01-20', 30.00, 'EUR', 'DBIT', 'BOOK',"
            + " 'passthrough', 'SYNTHETIC WALLET', 'CDTR-WALLET', 'WALLET-MANDATE', '{}'::jsonb)",
        importId);
  }

  private long seedNameCounterparty(String name) {
    return db.fetchOne(
            "INSERT INTO counterparties (identity_type, identity_value, display_name)"
                + " VALUES ('name', ?, ?) RETURNING id",
            name,
            name)
        .get("id", Long.class);
  }

  private void seedRule() {
    db.execute(
        "INSERT INTO product_rules (creditor_id, position_pattern) VALUES (?, ?)",
        CREDITOR,
        PATTERN);
  }

  private List<String> childProducts() {
    return db.fetch(
            "SELECT product FROM transactions WHERE split_parent_content_hash IS NOT NULL"
                + " ORDER BY product")
        .map(r -> r.get("product", String.class));
  }

  private List<String> contractProducts() {
    return db.fetch("SELECT product FROM contracts ORDER BY product NULLS LAST")
        .map(r -> r.get("product", String.class));
  }
}
