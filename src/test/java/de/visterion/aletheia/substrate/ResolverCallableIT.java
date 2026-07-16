package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
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
 * Verifies both resolvers expose a directly callable, idempotent {@code resolve()} entry point
 * (task-5 brief), independent of the {@code ApplicationRunner#run} startup path -- the upcoming
 * HTTP ingest endpoint invokes {@code resolve()} directly after each ingest.
 */
class ResolverCallableIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private void booking(String creditorId, String mandateId, String isoDate, String amount) {
    long imp =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "synthetic.json")
            .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "hash-" + UUID.randomUUID())
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.parse(isoDate))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  @Test
  void resolversAreCallableDirectlyAndIdempotent() {
    booking("CDTR-CALLABLE", "MND-CALLABLE", "2026-01-01", "42.00");
    booking("CDTR-CALLABLE", "MND-CALLABLE", "2026-02-01", "42.00");

    counterpartyResolver.resolve();
    contractResolver.resolve();
    counterpartyResolver.resolve(); // second call must not throw or duplicate
    contractResolver.resolve();

    assertThat(db.fetchCount(COUNTERPARTIES)).isEqualTo(1);
    assertThat(
            db.fetchCount(
                CONTRACTS.join(COUNTERPARTIES).on(CONTRACTS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID)),
                COUNTERPARTIES.IDENTITY_VALUE.eq("CDTR-CALLABLE")))
        .isEqualTo(1);
    assertThat(db.fetchCount(RECURRING)).isEqualTo(1);
  }
}
