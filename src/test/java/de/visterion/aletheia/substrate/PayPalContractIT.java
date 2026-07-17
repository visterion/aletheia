package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PayPalContractIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;

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
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void attributed(long imp, String hash, LocalDate date, String merchant) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, date)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-PP-CREDITOR")
        .set(TRANSACTIONS.MANDATE_ID, "PP-SHARED-MANDATE")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "PayPal Europe S.a.r.l.")
        .set(TRANSACTIONS.ATTRIBUTED_NAME, merchant)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void recurringMerchantsBecomeSeparateAttributedContractsNoLumpedPaypal() {
    long imp = importId();
    // Two recurring merchants on the SAME shared PayPal mandate.
    attributed(imp, "fz-1", LocalDate.of(2026, 1, 1), "Fizz Media");
    attributed(imp, "fz-2", LocalDate.of(2026, 2, 1), "Fizz Media");
    attributed(imp, "ac-1", LocalDate.of(2026, 1, 5), "Acme Streaming");
    attributed(imp, "ac-2", LocalDate.of(2026, 2, 5), "Acme Streaming");
    // A once-only merchant -> counterparty but no contract.
    attributed(imp, "px-1", LocalDate.of(2026, 1, 9), "Pixel Games");

    counterpartyResolver.resolve();
    contractResolver.resolve();

    var contracts =
        db.select(COUNTERPARTIES.IDENTITY_VALUE, CONTRACTS.MANDATE_ID)
            .from(CONTRACTS)
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .fetch();

    assertThat(contracts)
        .extracting(
            r -> r.get(COUNTERPARTIES.IDENTITY_VALUE), r -> r.get(CONTRACTS.MANDATE_ID))
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("FIZZ MEDIA", "attributed"),
            org.assertj.core.groups.Tuple.tuple("ACME STREAMING", "attributed"));
    // No lumped contract on the PayPal creditor identity or its shared mandate.
    assertThat(
            db.fetchExists(
                db.selectOne()
                    .from(CONTRACTS)
                    .where(CONTRACTS.MANDATE_ID.eq("PP-SHARED-MANDATE"))))
        .isFalse();
  }
}
