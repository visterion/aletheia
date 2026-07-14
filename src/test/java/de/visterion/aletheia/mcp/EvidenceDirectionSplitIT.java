package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EvidenceDirectionSplitIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired ReadTools readTools;

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

  @Test
  void debitAndCreditSplitExcludeEachOther() {
    long imp = importId();
    for (int i = 0; i < 11; i++) {
      insertTxn(imp, "d" + i, LocalDate.now().minusDays(i + 1), "10.00", "DBIT", "CDTR-MIX", null, "Mix Co");
    }
    insertTxn(imp, "c0", LocalDate.now().minusDays(5), "30.00", "CRDT", "CDTR-MIX", null, "Mix Co");
    resolver.run(null);
    Record rec =
        db.fetchOne(
            "SELECT debit_last_365d, credit_last_365d, credit_total "
                + "FROM v_counterparty_evidence e JOIN counterparties c ON c.id = e.counterparty_id "
                + "WHERE c.identity_value = 'CDTR-MIX'");
    assertThat(rec.get("debit_last_365d", BigDecimal.class)).isEqualByComparingTo("110.00");
    assertThat(rec.get("credit_last_365d", BigDecimal.class)).isEqualByComparingTo("30.00");
    assertThat(rec.get("credit_total", BigDecimal.class)).isEqualByComparingTo("30.00");
  }

  @Test
  void annualCostUsesDebitOnlyFallback() { // AnnualCost, irregular/no-recurring path
    long imp = importId();
    for (int i = 0; i < 11; i++) {
      insertTxn(imp, "d" + i, LocalDate.now().minusDays(i + 1), "10.00", "DBIT", "CDTR-MIX2", null, "Mix2");
    }
    insertTxn(imp, "c0", LocalDate.now().minusDays(5), "30.00", "CRDT", "CDTR-MIX2", null, "Mix2");
    resolver.run(null);
    var queue = readTools.getReviewQueue(null, true);
    var mix = queue.stream().filter(e -> e.displayName().equals("Mix2")).findFirst().orElseThrow();
    assertThat(mix.annualCostEstimate()).isEqualByComparingTo("110.00"); // refund NOT added
  }
}
