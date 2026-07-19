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
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CounterpartySelectorResolverIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired CounterpartySelectorResolver selectorResolver;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void insertTxn(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String direction,
      String creditorId,
      String iban,
      String name) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, direction)
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, iban)
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

  private void seedFixtures() {
    long imp = importId();
    // Alpha: untagged, DBIT, spend 300 (above the 100 threshold).
    insertTxn(imp, "hash-alpha-1", LocalDate.now().minusDays(10), "150.00", "DBIT", "CDTR-ALPHA", null, "Alpha");
    insertTxn(imp, "hash-alpha-2", LocalDate.now().minusDays(40), "150.00", "DBIT", "CDTR-ALPHA", null, "Alpha");
    // Beta: tagged, CRDT.
    insertTxn(imp, "hash-beta-1", LocalDate.now().minusDays(5), "200.00", "CRDT", "CDTR-BETA", null, "Beta");
    // Gamma: untagged, DBIT, spend 5 (below the 100 threshold).
    insertTxn(imp, "hash-gamma-1", LocalDate.now().minusDays(5), "5.00", "DBIT", "CDTR-GAMMA", null, "Gamma");
    resolver.run(null);

    long betaId = counterpartyIdFor("CDTR-BETA");
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, betaId)
        .set(COUNTERPARTY_TAGS.DIMENSION, "domain")
        .set(COUNTERPARTY_TAGS.VALUE, "income")
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .execute();
  }

  @Test
  void resolvesUntaggedDbitAboveThreshold() {
    seedFixtures();

    List<Long> ids =
        selectorResolver.resolve(
            new CounterpartySelector(true, null, new BigDecimal("100"), Direction.DBIT, null, null, null, null, null, null, null, null, null, null, null));

    assertThat(ids).containsExactly(counterpartyIdFor("CDTR-ALPHA"));
  }

  @Test
  void namePatternIsCaseInsensitive() {
    seedFixtures();

    List<Long> ids = selectorResolver.resolve(new CounterpartySelector(null, "alph", null, null, null, null, null, null, null, null, null, null, null, null, null));

    assertThat(ids).contains(counterpartyIdFor("CDTR-ALPHA"));
  }

  @Test
  void bothIsRejected() {
    seedFixtures();

    assertThatThrownBy(
            () ->
                selectorResolver.resolve(
                    new CounterpartySelector(null, null, null, Direction.BOTH, null, null, null, null, null, null, null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private CounterpartySelector sel(
      Long txnCountMax,
      java.util.List<String> natureNotIn,
      java.util.List<String> domainNotIn,
      BigDecimal amountMin,
      BigDecimal amountMax,
      LocalDate lastSeenBefore,
      LocalDate lastSeenAfter) {
    return new CounterpartySelector(
        null, null, null, null, null, null, null, null,
        txnCountMax, natureNotIn, domainNotIn, amountMin, amountMax, lastSeenBefore, lastSeenAfter);
  }

  /** Seeds a counterparty whose only booking is a split parent, so it has no v_counterparty_evidence row. */
  private long seedNoEvidenceCounterparty() {
    long imp = importId();
    insertTxn(imp, "hash-delta-root", LocalDate.now().minusDays(3), "80.00", "DBIT", "CDTR-DELTA", null, "Delta");
    resolver.run(null); // creates the Delta counterparty (+ its evidence, for now)
    // A split child supersedes the root -> the root is NOT a logical leaf -> Delta drops out of the view.
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "hash-delta-child")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now().minusDays(3))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("80.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Bargeld")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, "hash-delta-root")
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();
    return counterpartyIdFor("CDTR-DELTA");
  }

  @Test
  void txnCountMaxSelectsFewTxnCounterparties() {
    seedFixtures();
    List<Long> ids =
        selectorResolver.resolve(sel(1L, null, null, null, null, null, null));
    assertThat(ids)
        .contains(counterpartyIdFor("CDTR-BETA"), counterpartyIdFor("CDTR-GAMMA"))
        .doesNotContain(counterpartyIdFor("CDTR-ALPHA")); // Alpha has 2 txns
  }

  @Test
  void txnCountMaxMatchesNoEvidenceCounterpartyAsZero() {
    long deltaId = seedNoEvidenceCounterparty();
    List<Long> ids = selectorResolver.resolve(sel(1L, null, null, null, null, null, null));
    assertThat(ids).contains(deltaId); // coalesce(txn_count,0)=0 <= 1
  }

  @Test
  void amountMaxExcludesLargeSingleBookings() {
    seedFixtures();
    List<Long> ids =
        selectorResolver.resolve(sel(null, null, null, null, new BigDecimal("100"), null, null));
    assertThat(ids)
        .contains(counterpartyIdFor("CDTR-GAMMA")) // amount_max 5 <= 100
        .doesNotContain(counterpartyIdFor("CDTR-ALPHA"), counterpartyIdFor("CDTR-BETA")); // 150, 200 > 100
  }

  @Test
  void amountFiltersExcludeNoEvidenceCounterparty() {
    long deltaId = seedNoEvidenceCounterparty();
    List<Long> ids =
        selectorResolver.resolve(sel(null, null, null, null, new BigDecimal("100"), null, null));
    assertThat(ids).doesNotContain(deltaId); // NULL amount_max fails the predicate
  }

  @Test
  void amountMinCountsCreditBookings() {
    seedFixtures();
    List<Long> ids =
        selectorResolver.resolve(sel(null, null, null, new BigDecimal("160"), null, null, null));
    assertThat(ids)
        .contains(counterpartyIdFor("CDTR-BETA")) // CRDT amount_max 200 >= 160
        .doesNotContain(counterpartyIdFor("CDTR-ALPHA"), counterpartyIdFor("CDTR-GAMMA"));
  }

  @Test
  void domainNotInExcludesTaggedKeepsUntagged() {
    seedFixtures();
    List<Long> ids =
        selectorResolver.resolve(sel(null, null, List.of("income"), null, null, null, null));
    assertThat(ids)
        .contains(counterpartyIdFor("CDTR-ALPHA"), counterpartyIdFor("CDTR-GAMMA")) // untagged pass
        .doesNotContain(counterpartyIdFor("CDTR-BETA")); // domain=income excluded
  }

  @Test
  void lastSeenBeforeAndAfterAreInclusiveWindows() {
    seedFixtures();
    List<Long> before =
        selectorResolver.resolve(sel(null, null, null, null, null, LocalDate.now().minusDays(7), null));
    assertThat(before).contains(counterpartyIdFor("CDTR-ALPHA")); // last_seen now-10 <= now-7
    List<Long> after =
        selectorResolver.resolve(sel(null, null, null, null, null, null, LocalDate.now().minusDays(7)));
    assertThat(after)
        .contains(counterpartyIdFor("CDTR-BETA"), counterpartyIdFor("CDTR-GAMMA")) // now-5 >= now-7
        .doesNotContain(counterpartyIdFor("CDTR-ALPHA"));
  }

  @Test
  void emptyNotInListsAndNegativeNumbersAreRejected() {
    assertThatThrownBy(() -> selectorResolver.resolve(sel(null, List.of(), null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selectorResolver.resolve(sel(null, null, List.of(), null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selectorResolver.resolve(sel(-1L, null, null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selectorResolver.resolve(sel(null, null, null, new BigDecimal("-1"), null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selectorResolver.resolve(sel(null, null, null, null, new BigDecimal("-1"), null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void combinedDrainSelectorSelectsOnlyOneOffNoise() {
    seedFixtures();
    // {txnCountMax:1, domainNotIn:[income], amountMax:2000, reviewed:false, hasContract:false}
    CounterpartySelector drain =
        new CounterpartySelector(
            null, null, null, null, null, null, false, false,
            1L, List.of("fixkosten"), List.of("income"), null, new BigDecimal("2000"), null, null);
    List<Long> ids = selectorResolver.resolve(drain);
    assertThat(ids)
        .contains(counterpartyIdFor("CDTR-GAMMA")) // 1 txn, untagged, 5 <= 2000, not reviewed, no contract
        .doesNotContain(counterpartyIdFor("CDTR-ALPHA")) // 2 txns
        .doesNotContain(counterpartyIdFor("CDTR-BETA")); // domain=income
  }
}
