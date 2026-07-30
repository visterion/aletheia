package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code merge_counterparty} at product grain (spec §7, review M2).
 *
 * <p>{@code CounterpartyMergeService.migrateOneContract} located the colliding target contract by
 * mandate alone and with {@code fetchOne()}. As soon as one mandate carries several product
 * contracts that throws {@code TooManyRowsException} and any merge touching that creditor aborts
 * mid-transaction; and even with one row per side, a {@code (mandate, HEALTH)} contract would be
 * treated as colliding with {@code (mandate, LEGAL)} and one of the two human confirmations would
 * be destroyed. The collision key is therefore {@code (mandate_id, product)}, NULL-safe on both
 * sides.
 *
 * <p>Fixtures are hand-invented: {@code CDTR-INSURER}, {@code SYNTH-INSURER-IBAN}, {@code
 * POLICY-1}, products {@code HEALTH}/{@code LEGAL}.
 */
class MergeWithProductContractsIT extends AbstractPostgresIT {

  private static final String MANDATE = "POLICY-1";

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_history, counterparty_tags,"
            + " counterparty_alias, tag_rules, counterparties, transactions, imports"
            + " RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE product_rules RESTART IDENTITY CASCADE");
  }

  @Test
  void mergeWithSeveralProductContractsOnOneMandateDoesNotThrow() {
    long target = insertCounterparty("creditor_id", "CDTR-INSURER");
    long source = insertCounterparty("iban", "SYNTH-INSURER-IBAN");
    long targetHealth = insertContract(target, MANDATE, "HEALTH");
    long targetLegal = insertContract(target, MANDATE, "LEGAL");
    insertRecurring(target, targetHealth, "100.00");
    insertRecurring(target, targetLegal, "50.00");
    long sourceHealth = insertContract(source, MANDATE, "HEALTH");
    insertRecurring(source, sourceHealth, "100.00");

    assertThatCode(
            () -> writeTools.mergeCounterparty(target, List.of(source), "same insurer, two identities"))
        .doesNotThrowAnyException();

    assertThat(productsOn(target)).containsExactlyInAnyOrder("HEALTH", "LEGAL");
    assertThat(db.fetchCount(CONTRACTS, CONTRACTS.COUNTERPARTY_ID.eq(source))).isZero();
  }

  @Test
  void collisionIsResolvedPerMandateAndProduct() {
    long target = insertCounterparty("creditor_id", "CDTR-INSURER");
    long source = insertCounterparty("iban", "SYNTH-INSURER-IBAN");
    long targetLegal = insertContract(target, MANDATE, "LEGAL");
    insertRecurring(target, targetLegal, "50.00");
    long sourceHealth = insertContract(source, MANDATE, "HEALTH");
    insertRecurring(source, sourceHealth, "100.00");

    writeTools.mergeCounterparty(target, List.of(source), "same insurer, two identities");

    // HEALTH is a different obligation from LEGAL: it must move over intact rather than be
    // dropped as a collision, and LEGAL must keep its own identity.
    assertThat(productsOn(target)).containsExactlyInAnyOrder("HEALTH", "LEGAL");
    assertThat(cellIdOf(target, "HEALTH")).isEqualTo("cell-HEALTH");
    assertThat(cellIdOf(target, "LEGAL")).isEqualTo("cell-LEGAL");
    assertThat(typicalAmountOf(target, "HEALTH")).isEqualByComparingTo("100.00");
    assertThat(typicalAmountOf(target, "LEGAL")).isEqualByComparingTo("50.00");
  }

  // --- fixture helpers ---

  private long insertCounterparty(String identityType, String identityValue) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, identityType)
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, identityValue)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  /** A human-authored (confirmed) product contract -- the only kind a merge migrates. */
  private long insertContract(long counterpartyId, String mandateId, String product) {
    return db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
        .set(CONTRACTS.MANDATE_ID, mandateId)
        .set(CONTRACTS.PRODUCT, product)
        .set(CONTRACTS.SOURCE, "confirmed")
        .set(CONTRACTS.STATUS, "confirmed")
        .set(CONTRACTS.CONFIRMED_AT, OffsetDateTime.now())
        .set(CONTRACTS.HIVEMEM_CELL_ID, "cell-" + product)
        .set(CONTRACTS.NOTES, "notes-" + product)
        .returning(CONTRACTS.ID)
        .fetchOne(CONTRACTS.ID);
  }

  private void insertRecurring(long counterpartyId, long contractId, String typicalAmount) {
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
        .set(RECURRING.CONTRACT_ID, contractId)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal(typicalAmount))
        .set(RECURRING.AMOUNT_MIN, new BigDecimal(typicalAmount))
        .set(RECURRING.AMOUNT_MAX, new BigDecimal(typicalAmount))
        .set(RECURRING.SOURCE, "confirmed")
        .execute();
  }

  private List<String> productsOn(long counterpartyId) {
    return db.select(CONTRACTS.PRODUCT)
        .from(CONTRACTS)
        .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
        .fetch(CONTRACTS.PRODUCT);
  }

  private String cellIdOf(long counterpartyId, String product) {
    return db.select(CONTRACTS.HIVEMEM_CELL_ID)
        .from(CONTRACTS)
        .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
        .and(CONTRACTS.PRODUCT.eq(product))
        .fetchOne(CONTRACTS.HIVEMEM_CELL_ID);
  }

  private BigDecimal typicalAmountOf(long counterpartyId, String product) {
    return db.select(RECURRING.TYPICAL_AMOUNT)
        .from(RECURRING)
        .join(CONTRACTS)
        .on(CONTRACTS.ID.eq(RECURRING.CONTRACT_ID))
        .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
        .and(CONTRACTS.PRODUCT.eq(product))
        .fetchOne(RECURRING.TYPICAL_AMOUNT);
  }
}
