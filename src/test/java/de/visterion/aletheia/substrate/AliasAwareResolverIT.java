package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.tagrules.TagRuleResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 2 (sub-project A/P1): once a source identity is aliased into a target (V15 {@code
 * counterparty_alias}), a resolver re-run must not resurrect a {@code counterparties} row for the
 * folded source, and derived measures ({@code contracts}/{@code recurring}) must pool onto the
 * target instead of the source.
 */
class AliasAwareResolverIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;
  @Autowired TagRuleResolver tagRuleResolver;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "counterparty_alias, transactions, tag_rules, imports, counterparties "
            + "RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private long insertCounterparty(String identityType, String identityValue) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, identityType)
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, identityValue)
        .returning(COUNTERPARTIES.ID)
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

  private void booking(
      String contentHash,
      String creditorId,
      String mandateId,
      String attributedName,
      LocalDate bookingDate,
      String amount) {
    long imp = importId();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.ATTRIBUTED_NAME, attributedName)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, attributedName == null ? null : "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private int counterpartyCountFor(String identityType, String identityValue) {
    return db.fetchCount(
        COUNTERPARTIES,
        COUNTERPARTIES.IDENTITY_TYPE.eq(identityType).and(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue)));
  }

  private int contractCount(long counterpartyId) {
    return db.fetchCount(CONTRACTS, CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId));
  }

  private int recurringCount(long counterpartyId) {
    return db.fetchCount(RECURRING, RECURRING.COUNTERPARTY_ID.eq(counterpartyId));
  }

  @Test
  void resolverRerunAfterMergeCreatesNoGhostRowAndPoolsOntoTarget() {
    // Counterparty A (target): creditor_id identity, 2 months of MND-A -> auto contract+recurring.
    long a = insertCounterparty("creditor_id", "CDTR-A9");
    booking("hash-a9-1", "CDTR-A9", "MND-A9", null, LocalDate.of(2026, 1, 1), "10.00");
    booking("hash-a9-2", "CDTR-A9", "MND-A9", null, LocalDate.of(2026, 2, 1), "10.00");

    // Counterparty B (folded source): creditor_id identity, 2 months of MND-B -> also has its
    // own auto contract+recurring before the merge.
    long b = insertCounterparty("creditor_id", "CDTR-B9");
    booking("hash-b9-1", "CDTR-B9", "MND-B9", null, LocalDate.of(2026, 1, 1), "5.00");
    booking("hash-b9-2", "CDTR-B9", "MND-B9", null, LocalDate.of(2026, 2, 1), "5.00");

    counterpartyResolver.resolve();
    contractResolver.resolve();
    assertThat(contractCount(a)).isEqualTo(1);
    assertThat(contractCount(b)).isEqualTo(1);

    // B is folded into A.
    insertAlias("creditor_id", "CDTR-B9", a);

    int counterpartiesBefore = db.fetchCount(COUNTERPARTIES);

    // Re-run all three resolvers -- must not resurrect B's identity nor create ghost contracts.
    counterpartyResolver.resolve();
    contractResolver.resolve();
    tagRuleResolver.resolve();

    assertThat(db.fetchCount(COUNTERPARTIES)).isEqualTo(counterpartiesBefore); // no ghost row
    assertThat(counterpartyCountFor("creditor_id", "CDTR-B9")).isEqualTo(1); // B's row untouched

    // A now has both its own contract (MND-A9) and B's pooled contract (MND-B9).
    assertThat(contractCount(a)).isEqualTo(2);
    assertThat(recurringCount(a)).isEqualTo(2);
  }

  @Test
  void twoAliasedAttributedFragmentsPoolIntoOneRecurringRowWithoutConflictError() {
    // Target counterparty for the merge.
    long target = insertCounterparty("name", "CANONICAL MERCHANT");

    // Two distinct attributed-name fragments (e.g. two PayPal creditor passthroughs
    // attributing to slightly different raw merchant strings) that both fold into the target.
    booking(
        "hash-frag1-1",
        "SYNTH-PP-1",
        "PP-MANDATE-1",
        "Fragment One",
        LocalDate.of(2026, 1, 1),
        "12.00");
    booking(
        "hash-frag1-2",
        "SYNTH-PP-1",
        "PP-MANDATE-1",
        "Fragment One",
        LocalDate.of(2026, 2, 1),
        "12.00");
    booking(
        "hash-frag2-1",
        "SYNTH-PP-2",
        "PP-MANDATE-2",
        "Fragment Two",
        LocalDate.of(2026, 1, 1),
        "8.00");
    booking(
        "hash-frag2-2",
        "SYNTH-PP-2",
        "PP-MANDATE-2",
        "Fragment Two",
        LocalDate.of(2026, 2, 1),
        "8.00");

    // Both attributed identities (name identity, normalized upper) are aliased to the same
    // target -- both map through the synthetic 'attributed' mandate -> same (target, mandate)
    // contract grain.
    insertAlias("name", "FRAGMENT ONE", target);
    insertAlias("name", "FRAGMENT TWO", target);

    assertThatCode(
            () -> {
              counterpartyResolver.resolve();
              contractResolver.resolve();
              tagRuleResolver.resolve();
            })
        .doesNotThrowAnyException();

    // Exactly one contract and one recurring row for the target's 'attributed' mandate grain --
    // no "ON CONFLICT cannot affect row a second time" (SQL 21000).
    assertThat(contractCount(target)).isEqualTo(1);
    assertThat(recurringCount(target)).isEqualTo(1);

    int occurrenceCount =
        db.select(RECURRING.OCCURRENCE_COUNT)
            .from(RECURRING)
            .where(RECURRING.COUNTERPARTY_ID.eq(target))
            .fetchOne(RECURRING.OCCURRENCE_COUNT);
    assertThat(occurrenceCount).isEqualTo(4); // both fragments' bookings pooled together
  }
}
