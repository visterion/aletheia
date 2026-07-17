package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReattributeReadParityIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired ReadTools readTools;
  @Autowired CounterpartyResolver counterpartyResolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE transactions, counterparties, imports RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  /** A passthrough booking (creditor SYNTH-ADYEN) already stamped attributed_name='Fizz Media'. */
  private void insertAttributed(long imp, String hash, String attributed) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-ADYEN")
        .set(TRANSACTIONS.ATTRIBUTED_NAME, attributed)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "manual")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void counterpartyTransactionsSurfacesAttributedRowsUnderMerchantWithRefs() {
    long imp = importId();
    insertAttributed(imp, "hash-1", "Fizz Media");
    insertAttributed(imp, "hash-2", "Fizz Media");
    counterpartyResolver.resolve();

    long merchantId =
        ((Number)
                db.fetchValue(
                    "SELECT id FROM counterparties WHERE identity_type = 'name' AND identity_value = 'FIZZ MEDIA'"))
            .longValue();

    List<TransactionView> merchantTx =
        readTools.counterpartyTransactions(merchantId, null, null, null);
    assertThat(merchantTx).hasSize(2);
    assertThat(merchantTx).extracting(TransactionView::contentHash)
        .containsExactlyInAnyOrder("hash-1", "hash-2");
    assertThat(merchantTx).allSatisfy(t -> assertThat(t.occurrenceIndex()).isEqualTo(0));

    // The passthrough creditor identity never owns these bookings: CounterpartyResolver
    // prioritizes attributed_name over creditor_id, so no creditor_id counterparty for
    // SYNTH-ADYEN is ever materialized while both its transactions are attributed.
    Number adyenCount =
        (Number)
            db.fetchValue(
                "SELECT COUNT(*) FROM counterparties WHERE identity_type = 'creditor_id' AND identity_value = 'SYNTH-ADYEN'");
    assertThat(adyenCount.intValue()).isZero();
  }
}
