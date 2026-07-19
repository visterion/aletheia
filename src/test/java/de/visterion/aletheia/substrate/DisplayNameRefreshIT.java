package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Task 2 (Spec B/P2): {@link CounterpartyResolver#resolve()} must derive {@code display_name} as
 * the most-frequent normalized name over a counterparty's bookings (alias-aware, over the merged
 * set of folded source identities), tying on the youngest (most recent) booking, rather than the
 * latest booking's name.
 */
class DisplayNameRefreshIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_alias, transactions, imports, counterparties "
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

  private void insertTxn(
      String contentHash, LocalDate bookingDate, String iban, String name) {
    long imp = importId();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, iban)
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private String displayName(long counterpartyId) {
    return db.select(COUNTERPARTIES.DISPLAY_NAME)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .fetchOne(COUNTERPARTIES.DISPLAY_NAME);
  }

  @Test
  void ownerChangeUsesMostFrequentNotLatest() {
    long a = insertCounterparty("iban", "IBAN-OWNER-CHANGE");
    insertTxn("hash-owner-1", LocalDate.of(2026, 1, 1), "IBAN-OWNER-CHANGE", "OLD SHOP");
    insertTxn("hash-owner-2", LocalDate.of(2026, 1, 2), "IBAN-OWNER-CHANGE", "OLD SHOP");
    insertTxn("hash-owner-3", LocalDate.of(2026, 2, 1), "IBAN-OWNER-CHANGE", "NEW SHOP");
    insertTxn("hash-owner-4", LocalDate.of(2026, 2, 2), "IBAN-OWNER-CHANGE", "NEW SHOP");
    insertTxn("hash-owner-5", LocalDate.of(2026, 2, 3), "IBAN-OWNER-CHANGE", "NEW SHOP");
    insertTxn("hash-owner-6", LocalDate.of(2026, 2, 4), "IBAN-OWNER-CHANGE", "NEW SHOP");
    insertTxn("hash-owner-7", LocalDate.of(2026, 2, 5), "IBAN-OWNER-CHANGE", "NEW SHOP");

    counterpartyResolver.resolve();

    assertThat(displayName(a)).isEqualTo("NEW SHOP");
  }

  @Test
  void frequencyTieBreaksToYoungest() {
    long a = insertCounterparty("iban", "IBAN-TIE");
    insertTxn("hash-tie-a-1", LocalDate.of(2026, 1, 1), "IBAN-TIE", "A");
    insertTxn("hash-tie-a-2", LocalDate.of(2026, 1, 2), "IBAN-TIE", "A");
    insertTxn("hash-tie-a-3", LocalDate.of(2026, 1, 3), "IBAN-TIE", "A");
    insertTxn("hash-tie-b-1", LocalDate.of(2026, 2, 1), "IBAN-TIE", "B");
    insertTxn("hash-tie-b-2", LocalDate.of(2026, 2, 2), "IBAN-TIE", "B");
    insertTxn("hash-tie-b-3", LocalDate.of(2026, 2, 3), "IBAN-TIE", "B");

    counterpartyResolver.resolve();

    assertThat(displayName(a)).isEqualTo("B");
  }

  @Test
  void aliasAwareMostFrequentOverMergedSet() {
    long target = insertCounterparty("name", "BAR");
    long source = insertCounterparty("name", "FOO");

    // Target identity: 2 bookings of "Bar".
    insertTxn("hash-alias-bar-1", LocalDate.of(2026, 1, 1), null, "Bar");
    insertTxn("hash-alias-bar-2", LocalDate.of(2026, 1, 2), null, "Bar");

    // Source identity: 3 bookings of "Foo" -- more frequent over the merged set.
    insertTxn("hash-alias-foo-1", LocalDate.of(2026, 1, 3), null, "Foo");
    insertTxn("hash-alias-foo-2", LocalDate.of(2026, 1, 4), null, "Foo");
    insertTxn("hash-alias-foo-3", LocalDate.of(2026, 1, 5), null, "Foo");

    insertAlias("name", "FOO", target);
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.MERGED_INTO, target)
        .where(COUNTERPARTIES.ID.eq(source))
        .execute();

    counterpartyResolver.resolve();

    assertThat(displayName(target)).isEqualTo("Foo");
  }

  @Test
  void singleResolveWritesMostFrequentNotLatest() {
    long a = insertCounterparty("iban", "IBAN-NODOUBLE");
    insertTxn("hash-nodouble-1", LocalDate.of(2026, 1, 1), "IBAN-NODOUBLE", "FREQUENT NAME");
    insertTxn("hash-nodouble-2", LocalDate.of(2026, 1, 2), "IBAN-NODOUBLE", "FREQUENT NAME");
    insertTxn("hash-nodouble-3", LocalDate.of(2026, 1, 3), "IBAN-NODOUBLE", "FREQUENT NAME");
    insertTxn("hash-nodouble-4", LocalDate.of(2026, 2, 1), "IBAN-NODOUBLE", "LATEST NAME");

    counterpartyResolver.resolve();

    assertThat(displayName(a)).isEqualTo("FREQUENT NAME");
  }
}
