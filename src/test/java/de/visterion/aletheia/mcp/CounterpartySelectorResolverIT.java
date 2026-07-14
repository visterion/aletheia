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
            new CounterpartySelector(true, null, new BigDecimal("100"), Direction.DBIT));

    assertThat(ids).containsExactly(counterpartyIdFor("CDTR-ALPHA"));
  }

  @Test
  void namePatternIsCaseInsensitive() {
    seedFixtures();

    List<Long> ids = selectorResolver.resolve(new CounterpartySelector(null, "alph", null, null));

    assertThat(ids).contains(counterpartyIdFor("CDTR-ALPHA"));
  }

  @Test
  void bothIsRejected() {
    seedFixtures();

    assertThatThrownBy(
            () ->
                selectorResolver.resolve(
                    new CounterpartySelector(null, null, null, Direction.BOTH)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
