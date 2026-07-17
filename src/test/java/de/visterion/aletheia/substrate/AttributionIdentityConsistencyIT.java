package de.visterion.aletheia.substrate;

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

class AttributionIdentityConsistencyIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver counterpartyResolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  @Test
  void resolverAndBothViewsDeriveTheSameAttributedIdentity() {
    long imp =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "synthetic.json")
            .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "c1")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-PP-CREDITOR")
        .set(TRANSACTIONS.MANDATE_ID, "PP-SHARED-MANDATE")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "PayPal Europe S.a.r.l.")
        .set(TRANSACTIONS.ATTRIBUTED_NAME, "Fizz Media")
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "c2")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 2, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-PP-CREDITOR")
        .set(TRANSACTIONS.MANDATE_ID, "PP-SHARED-MANDATE")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "PayPal Europe S.a.r.l.")
        .set(TRANSACTIONS.ATTRIBUTED_NAME, "Fizz Media")
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();

    counterpartyResolver.resolve();

    // Resolver output: the merchant counterparty exists as a name identity.
    boolean resolverName =
        db.fetchExists(
            db.selectOne()
                .from(de.visterion.aletheia.jooq.Tables.COUNTERPARTIES)
                .where(de.visterion.aletheia.jooq.Tables.COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
                .and(
                    de.visterion.aletheia.jooq.Tables.COUNTERPARTIES.IDENTITY_VALUE.eq(
                        "FIZZ MEDIA")));
    assertThat(resolverName).isTrue();

    // v_counterparty_evidence joins that same identity.
    assertThat(
            db.fetch(
                "SELECT 1 FROM v_counterparty_evidence v JOIN counterparties c "
                    + "ON c.id = v.counterparty_id WHERE c.identity_value = 'FIZZ MEDIA'"))
        .hasSize(1);

    // v_contract_evidence keys the same identity with the synthetic 'attributed' mandate.
    var contract =
        db.fetch(
            "SELECT v.mandate_id FROM v_contract_evidence v JOIN counterparties c "
                + "ON c.id = v.counterparty_id WHERE c.identity_value = 'FIZZ MEDIA'");
    assertThat(contract).hasSize(1);
    assertThat(contract.get(0).get("mandate_id", String.class)).isEqualTo("attributed");
  }
}
