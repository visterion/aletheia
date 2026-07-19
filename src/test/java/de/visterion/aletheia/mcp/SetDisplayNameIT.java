package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 4 (Spec B): {@code set_display_name} sets/clears {@code
 * counterparties.display_name_override}. The override is a cosmetic label -- it wins at read time
 * (Task 3's COALESCE) but must never affect identity resolution or split-allocation routing (P2.4).
 */
class SetDisplayNameIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "counterparty_alias, transactions, imports, counterparties RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void insertTxn(
      long importId, String contentHash, LocalDate bookingDate, String creditorId, String name) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("30.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private long counterpartyIdFor(String identityValue) {
    return db.select(COUNTERPARTIES.ID)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue))
        .fetchOne(COUNTERPARTIES.ID);
  }

  private long seedNameIdentityCounterparty(String name) {
    long imp = importId();
    insertTxn(imp, "hash-sdn-" + UUID.randomUUID(), LocalDate.now().minusDays(10), null, name);
    insertTxn(imp, "hash-sdn-" + UUID.randomUUID(), LocalDate.now().minusDays(40), null, name);
    resolver.run(null);
    return counterpartyIdFor(name.toUpperCase());
  }

  @Test
  void setsTheOverrideAndSurfacesItInListCounterparties() {
    long cpId = seedNameIdentityCounterparty("Merchant A");

    var ack = writeTools.setDisplayName(cpId, "Custom");

    assertThat(ack.counterpartyId()).isEqualTo(cpId);

    var summary =
        readTools.listCounterparties(CounterpartyFilter.all, null).stream()
            .filter(s -> s.id() == cpId)
            .findFirst()
            .orElseThrow();
    assertThat(summary.displayName()).isEqualTo("Custom");

    Record history =
        db.selectFrom(COUNTERPARTY_HISTORY)
            .where(COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(cpId))
            .and(COUNTERPARTY_HISTORY.FIELD.eq("display_name_override"))
            .fetchOne();
    assertThat(history).isNotNull();
    assertThat(history.get(COUNTERPARTY_HISTORY.OLD_VALUE)).isNull();
    assertThat(history.get(COUNTERPARTY_HISTORY.NEW_VALUE)).isEqualTo("Custom");
  }

  @Test
  void clearingWithNullRevertsToTheDerivedDisplayName() {
    long cpId = seedNameIdentityCounterparty("Merchant B");
    writeTools.setDisplayName(cpId, "Custom");

    writeTools.setDisplayName(cpId, null);

    String override =
        db.select(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(cpId))
            .fetchOne(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE);
    assertThat(override).isNull();

    var summary =
        readTools.listCounterparties(CounterpartyFilter.all, null).stream()
            .filter(s -> s.id() == cpId)
            .findFirst()
            .orElseThrow();
    assertThat(summary.displayName()).isEqualTo("Merchant B");
  }

  @Test
  void rejectsAFoldedCounterparty() {
    long a = seedNameIdentityCounterparty("Merchant C1");
    long b = seedNameIdentityCounterparty("Merchant C2");
    db.execute(
        "INSERT INTO counterparty_alias (identity_type, identity_value, canonical_counterparty_id) "
            + "VALUES ('name', 'MERCHANT C2', ?)",
        a);
    db.update(COUNTERPARTIES).set(COUNTERPARTIES.MERGED_INTO, a).where(COUNTERPARTIES.ID.eq(b)).execute();

    assertThatThrownBy(() -> writeTools.setDisplayName(b, "X"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");
  }

  @Test
  void normalizesWhitespaceAndPreservesCase() {
    long cpId = seedNameIdentityCounterparty("Merchant D");

    writeTools.setDisplayName(cpId, "  Cafe   Ruby  ");

    String override =
        db.select(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(cpId))
            .fetchOne(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE);
    assertThat(override).isEqualTo("Cafe Ruby");
  }

  @Test
  void splitAllocationByTheOverrideNameMintsANewCounterpartyInsteadOfRoutingToTheOverriddenCp() {
    long cpId = seedNameIdentityCounterparty("Merchant E");
    writeTools.setDisplayName(cpId, "Custom");

    long imp = importId();
    String pHash = "parent-sdn-override";
    insertTxn(imp, pHash, LocalDate.now(), null, "Split Source E");

    var ack =
        writeTools.splitTransaction(
            new TxReference(pHash, 0),
            List.of(new Allocation(null, "Custom", null, new BigDecimal("30.00"), "x")),
            null);

    // The override is not an identity: "Custom" is not cpId's identity_value ("MERCHANT E"), so a
    // brand-new ('name', 'CUSTOM') counterparty is minted rather than routing to cpId.
    assertThat(ack.createdCounterpartyIds()).hasSize(1);
    long mintedId = ack.createdCounterpartyIds().get(0);
    assertThat(mintedId).isNotEqualTo(cpId);
    assertThat(
            db.fetchCount(
                COUNTERPARTIES,
                COUNTERPARTIES
                    .IDENTITY_TYPE
                    .eq("name")
                    .and(COUNTERPARTIES.IDENTITY_VALUE.eq("CUSTOM"))))
        .isEqualTo(1);
  }

  @Test
  void splitAllocationByTheUnderlyingRawNameStillRoutesToTheOverriddenCpRegardlessOfTheOverride() {
    long cpId = seedNameIdentityCounterparty("Merchant F");
    writeTools.setDisplayName(cpId, "Custom");

    long imp = importId();
    String pHash = "parent-sdn-identity";
    insertTxn(imp, pHash, LocalDate.now(), null, "Split Source F");

    var ack =
        writeTools.splitTransaction(
            new TxReference(pHash, 0),
            List.of(new Allocation(null, "Merchant F", null, new BigDecimal("30.00"), "x")),
            null);

    // The raw underlying identity ("MERCHANT F") still resolves to cpId even though its display
    // name is now overridden to "Custom" -- the override never touches identity resolution.
    assertThat(ack.createdCounterpartyIds()).isEmpty();
    List<TransactionView> childTxns = readTools.counterpartyTransactions(cpId, null, null, null);
    assertThat(childTxns).anyMatch(t -> "Merchant F".equals(t.counterpartyName()));
  }
}
