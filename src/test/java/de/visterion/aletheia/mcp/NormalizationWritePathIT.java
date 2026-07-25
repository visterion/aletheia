package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * All four write paths that produce a counterparty name -- resolver upsert, resolver display-name
 * refresh, {@code split_transaction} and {@code set_display_name} -- must derive names with the one
 * shared SQL formula, so that a name written by one path is byte-identical to what another path
 * would derive from the same input.
 *
 * <p>Java and PostgreSQL disagree about that formula (see {@code NameNormalization#evaluate}), and
 * the two Java-side write paths used to re-derive it locally. The regression this guards is
 * concrete: {@code split_transaction} with a name containing an em space kept the character and
 * minted a *second* counterparty for a merchant the resolver had already identified.
 *
 * <p>Every non-ASCII character below is built from its code point on purpose -- invisible
 * characters pasted into source are unreviewable, and this file is about invisible characters.
 */
class NormalizationWritePathIT extends AbstractPostgresIT {

  /** U+2003 EM SPACE: PostgreSQL's {@code \s} collapses it, Java's does not. */
  private static final String EM_SPACE = String.valueOf((char) 0x2003);

  /**
   * U+0085 NEXT LINE: PostgreSQL collapses and trims it away (so a lone NEL normalizes to the empty
   * string), while Java considers it neither blank nor trimmable.
   */
  private static final String NEL = String.valueOf((char) 0x0085);

  private static final String RAW = "{}";

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired WriteTools writeTools;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "counterparty_alias, transactions, imports, counterparties RESTART IDENTITY CASCADE");
  }

  // --- helpers ---

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  /** Inserts a raw (root) transaction: {@code split_parent_content_hash} stays NULL, or the
   * resolver's RAW_ROOT_PREDICATE would filter it out. */
  private void insertRawTxn(long importId, String contentHash, LocalDate bookingDate, String name) {
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
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private String parentTxn(String name) {
    String hash = "parent-norm-" + UUID.randomUUID();
    insertRawTxn(importId(), hash, LocalDate.now(), name);
    return hash;
  }

  private long seedNameCounterparty(String name) {
    long imp = importId();
    insertRawTxn(imp, "hash-norm-" + UUID.randomUUID(), LocalDate.now().minusDays(10), name);
    resolver.resolve();
    return counterpartyIdFor(name.toUpperCase(Locale.ROOT));
  }

  private long counterpartyIdFor(String identityValue) {
    return db.select(COUNTERPARTIES.ID)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
        .and(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue))
        .fetchOne(COUNTERPARTIES.ID);
  }

  private int countByIdentity(String identityValue) {
    return db.fetchCount(
        COUNTERPARTIES,
        COUNTERPARTIES
            .IDENTITY_TYPE
            .eq("name")
            .and(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue)));
  }

  private String displayNameOf(long cpId) {
    return db.select(COUNTERPARTIES.DISPLAY_NAME)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.ID.eq(cpId))
        .fetchOne(COUNTERPARTIES.DISPLAY_NAME);
  }

  private String overrideOf(long cpId) {
    return db.select(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.ID.eq(cpId))
        .fetchOne(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE);
  }

  private boolean hasNatureTag(long cpId, String value) {
    return db.fetchExists(
        db.selectOne()
            .from(COUNTERPARTY_TAGS)
            .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(cpId))
            .and(COUNTERPARTY_TAGS.DIMENSION.eq("nature"))
            .and(COUNTERPARTY_TAGS.VALUE.eq(value)));
  }

  private List<String> childNamesOf(String parentHash) {
    return db.select(TRANSACTIONS.COUNTERPARTY_NAME)
        .from(TRANSACTIONS)
        .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(parentHash))
        .orderBy(TRANSACTIONS.COUNTERPARTY_NAME)
        .fetch(TRANSACTIONS.COUNTERPARTY_NAME);
  }

  // --- set_display_name ---

  @Test
  void setDisplayNameCollapsesAnEmSpaceInsteadOfStoringItVerbatim() {
    long cpId = seedNameCounterparty("Merchant Norm A");

    writeTools.setDisplayName(cpId, "Cafe" + EM_SPACE + "Ruby");

    assertThat(overrideOf(cpId)).isEqualTo("Cafe Ruby");
  }

  @Test
  void setDisplayNameClearsTheOverrideWhenTheNameNormalizesToEmpty() {
    // Precondition that makes this case interesting: Java does not consider NEL blank, so the
    // raw-blankness pre-check in setDisplayName does not catch it -- only the isEmpty() re-check
    // on the SQL-normalized value does.
    assertThat(NEL.isBlank()).isFalse();

    long cpId = seedNameCounterparty("Merchant Norm B");
    writeTools.setDisplayName(cpId, "Custom");
    assertThat(overrideOf(cpId)).isEqualTo("Custom");

    writeTools.setDisplayName(cpId, NEL);

    // NULL, not '' -- reads use COALESCE(display_name_override, display_name), so an empty string
    // would render the counterparty nameless instead of falling back to the derived name.
    assertThat(overrideOf(cpId)).isNull();
  }

  // --- split_transaction ---

  @Test
  void splitWithAnEmSpaceNameReusesTheExistingCounterpartyInsteadOfMintingASecondOne() {
    db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "name")
        .set(COUNTERPARTIES.IDENTITY_VALUE, "SYNTHETIC MERCHANT")
        .set(COUNTERPARTIES.DISPLAY_NAME, "Synthetic Merchant")
        .set(COUNTERPARTIES.STATUS, "open")
        .execute();
    long existing = counterpartyIdFor("SYNTHETIC MERCHANT");
    String parent = parentTxn("Split Source Em");

    var ack =
        writeTools.splitTransaction(
            new TxReference(parent, 0),
            List.of(
                new Allocation(
                    null,
                    "Synthetic" + EM_SPACE + "Merchant",
                    null,
                    new BigDecimal("30.00"),
                    "x")),
            null);

    // This is the live bug: the Java helper kept the em space and minted ('name', 'SYNTHETIC<em
    // space>MERCHANT') as a second row for the same merchant.
    assertThat(ack.createdCounterpartyIds()).isEmpty();
    assertThat(countByIdentity("SYNTHETIC MERCHANT")).isEqualTo(1);
    assertThat(childNamesOf(parent)).containsExactly("Synthetic Merchant");
    assertThat(existing).isEqualTo(counterpartyIdFor("SYNTHETIC MERCHANT"));
  }

  @Test
  void splitWithANameThatNormalizesToEmptyIsRejectedAndRollsBack() {
    String parent = parentTxn("Split Source Nel");
    writeTools.splitTransaction(
        new TxReference(parent, 0),
        List.of(
            new Allocation(null, "Child One", null, new BigDecimal("10.00"), "a"),
            new Allocation(null, "Child Two", null, new BigDecimal("20.00"), "b")),
        null);
    assertThat(childNamesOf(parent)).containsExactly("Child One", "Child Two");

    assertThatThrownBy(
            () ->
                writeTools.splitTransaction(
                    new TxReference(parent, 0),
                    List.of(
                        new Allocation(null, "Child One", null, new BigDecimal("10.00"), "a"),
                        new Allocation(null, NEL, null, new BigDecimal("20.00"), "b")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalizes to empty");

    // splitTransaction is @Transactional and has replace semantics: the delete of the previous
    // children must have rolled back with the failed re-split, not left the parent unsplit.
    assertThat(childNamesOf(parent)).containsExactly("Child One", "Child Two");
    // Documents the intended ordering -- the empty check is hoisted above the counterparty ensure,
    // so no ('name', '') row is ever inserted. It is not a proof of that ordering: splitTransaction
    // is one transactional unit, so an insert that did happen would have rolled back with the
    // exception and be unobservable from here either way.
    assertThat(countByIdentity("")).isZero();
  }

  // --- resolver ---

  @Test
  void resolverUpsertStoresTheNormalizedDisplayName() {
    long imp = importId();
    insertRawTxn(
        imp, "hash-upsert-" + UUID.randomUUID(), LocalDate.now(), "  Padded  Merchant Name  ");

    resolver.resolve();

    long cpId = counterpartyIdFor("PADDED MERCHANT NAME");
    assertThat(displayNameOf(cpId)).isEqualTo("Padded Merchant Name");
  }

  @Test
  void resolverRefreshNormalizesAndFoldsSpellingsOntoOneCounterparty() {
    long imp = importId();
    insertRawTxn(
        imp, "hash-refresh-a-" + UUID.randomUUID(), LocalDate.now().minusDays(3),
        "  Folded  Merchant  ");
    insertRawTxn(
        imp, "hash-refresh-b-" + UUID.randomUUID(), LocalDate.now().minusDays(2), "Folded Merchant");
    insertRawTxn(
        imp, "hash-refresh-c-" + UUID.randomUUID(), LocalDate.now().minusDays(1), "Folded Merchant");

    resolver.resolve();

    assertThat(countByIdentity("FOLDED MERCHANT")).isEqualTo(1);
    long cpId = counterpartyIdFor("FOLDED MERCHANT");
    assertThat(displayNameOf(cpId)).isEqualTo("Folded Merchant");
  }

  // --- Bargeld ---

  @Test
  void plainBargeldStillMintsTheConstantNameAndTheNatureTag() {
    String parent = parentTxn("Cash Source");

    writeTools.splitTransaction(
        new TxReference(parent, 0),
        List.of(
            new Allocation(
                null,
                TransactionSplitService.BARGELD_DISPLAY_NAME,
                null,
                new BigDecimal("30.00"),
                "x")),
        null);

    long cpId = counterpartyIdFor("BARGELD");
    assertThat(displayNameOf(cpId)).isEqualTo(TransactionSplitService.BARGELD_DISPLAY_NAME);
    assertThat(hasNatureTag(cpId, TransactionSplitService.BARGELD_NATURE_VALUE)).isTrue();
  }

  @Test
  void bargeldWithAnEmSpaceResolvesToTheSameIdentityAndStillGetsTheNatureTag() {
    String parent = parentTxn("Cash Source Em");

    writeTools.splitTransaction(
        new TxReference(parent, 0),
        List.of(
            new Allocation(
                null,
                TransactionSplitService.BARGELD_DISPLAY_NAME + EM_SPACE,
                null,
                new BigDecimal("30.00"),
                "x")),
        null);

    assertThat(countByIdentity("BARGELD")).isEqualTo(1);
    long cpId = counterpartyIdFor("BARGELD");
    assertThat(displayNameOf(cpId)).isEqualTo(TransactionSplitService.BARGELD_DISPLAY_NAME);
    // The Bargeld check used to compare the *raw* input, so this padded spelling reached the
    // canonical BARGELD identity while silently skipping the tag that drives the obligations
    // register and the cashflow role mapping.
    assertThat(hasNatureTag(cpId, TransactionSplitService.BARGELD_NATURE_VALUE)).isTrue();
    assertThat(childNamesOf(parent))
        .containsExactly(TransactionSplitService.BARGELD_DISPLAY_NAME);
  }
}
