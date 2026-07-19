package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 4 (sub-project A/P1 counterparty merge): no write may land on a folded (soft-deleted,
 * {@code merged_into IS NOT NULL}) counterparty -- reads already hide it (Task 3), writes must
 * reject or reroute rather than silently mutate an invisible source.
 */
class WriteFoldedGuardIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired WriteTools writeTools;

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

  private void insertAlias(String identityType, String identityValue, long canonicalCounterpartyId) {
    db.execute(
        "INSERT INTO counterparty_alias (identity_type, identity_value, canonical_counterparty_id) "
            + "VALUES (?, ?, ?)",
        identityType,
        identityValue,
        canonicalCounterpartyId);
  }

  /**
   * Seeds counterparty A (target) and B (source, folded into A): both fed by real transactions so
   * the resolver actually creates them, then aliases B's creditor identity to A and marks B {@code
   * merged_into} A.
   *
   * @return {@code [a, b]} counterparty ids
   */
  private long[] seedMergedPair() {
    long imp = importId();
    insertTxn(imp, "hash-fg-a1", LocalDate.now().minusDays(10), "CDTR-FG-A", "Merchant A");
    insertTxn(imp, "hash-fg-a2", LocalDate.now().minusDays(40), "CDTR-FG-A", "Merchant A");
    insertTxn(imp, "hash-fg-b1", LocalDate.now().minusDays(10), "CDTR-FG-B", "Merchant B");
    insertTxn(imp, "hash-fg-b2", LocalDate.now().minusDays(40), "CDTR-FG-B", "Merchant B");

    resolver.run(null);

    long a = counterpartyIdFor("CDTR-FG-A");
    long b = counterpartyIdFor("CDTR-FG-B");

    insertAlias("creditor_id", "CDTR-FG-B", a);
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.MERGED_INTO, a)
        .where(COUNTERPARTIES.ID.eq(b))
        .execute();

    return new long[] {a, b};
  }

  @Test
  void classifyCounterpartyRejectsAFoldedCounterpartyId() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    assertThatThrownBy(
            () ->
                writeTools.classifyCounterparty(
                    List.of(b),
                    null,
                    List.of(new TagInput("domain", "telecom")),
                    TagSource.auto,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");
  }

  @Test
  void markRecurringRejectsAFoldedCounterpartyId() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    assertThatThrownBy(
            () ->
                writeTools.markRecurring(
                    b, null, Cadence.monthly, new BigDecimal("9.99"), null, null, TagSource.auto, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");
  }

  @Test
  void confirmCounterpartyRejectsAFoldedCounterpartyId() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    assertThatThrownBy(() -> writeTools.confirmCounterparty(b, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");
  }

  @Test
  void splitTransactionRejectsAnAllocationExplicitlyTargetingAFoldedCounterpartyId() {
    long[] ids = seedMergedPair();
    long b = ids[1];

    long imp = importId();
    String pHash = "parent-fold-001";
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, pHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now())
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("30.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Split Target")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();

    assertThatThrownBy(
            () ->
                writeTools.splitTransaction(
                    new TxReference(pHash, 0),
                    List.of(new Allocation(b, null, null, new BigDecimal("30.00"), "cash")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");
  }

  @Test
  void splitTransactionByDisplayNameResolvesAFoldedNameIdentityToTheCanonicalTarget() {
    long imp = importId();
    // A is a real, resolved counterparty. "Name Merchant Orphan" has never been booked before --
    // it exists only as an alias mapping onto A (e.g. a known name variant folded pre-emptively),
    // with no physical counterparties row of its own. Without alias routing,
    // ensureCounterpartyByDisplayName would find no existing row under that identity and INSERT a
    // brand-new counterparty instead of pooling onto A.
    insertTxn(imp, "hash-fg-name-a1", LocalDate.now().minusDays(10), null, "Name Merchant A");
    insertTxn(imp, "hash-fg-name-a2", LocalDate.now().minusDays(40), null, "Name Merchant A");
    resolver.run(null);

    long a = counterpartyIdFor("NAME MERCHANT A");
    insertAlias("name", "NAME MERCHANT ORPHAN", a);

    String pHash = "parent-fold-name-001";
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, pHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now())
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("15.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Split Source")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();

    var ack =
        writeTools.splitTransaction(
            new TxReference(pHash, 0),
            List.of(
                new Allocation(null, "Name Merchant Orphan", null, new BigDecimal("15.00"), "x")),
            null);

    // Resolved onto the existing canonical A -- no new counterparty was created for the alias.
    assertThat(ack.createdCounterpartyIds()).isEmpty();
    assertThat(
            db.fetchCount(
                COUNTERPARTIES,
                COUNTERPARTIES
                    .IDENTITY_TYPE
                    .eq("name")
                    .and(COUNTERPARTIES.IDENTITY_VALUE.eq("NAME MERCHANT ORPHAN"))))
        .isZero();
  }
}
