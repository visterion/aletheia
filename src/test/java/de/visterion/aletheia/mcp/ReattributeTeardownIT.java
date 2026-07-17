package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReattributeTeardownIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparties, transactions, imports"
            + " RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void insertAdyen(long imp, String hash, LocalDate date) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, date)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-ADYEN")
        .set(TRANSACTIONS.REMITTANCE_INFO, "Fizz Media")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  private long merchantId() {
    return ((Number)
            db.fetchValue(
                "SELECT id FROM counterparties WHERE identity_type = 'name'"
                    + " AND identity_value = 'FIZZ MEDIA'"))
        .longValue();
  }

  @Test
  void clearThenContractTargetedDismissDrainsBothQueues() {
    long imp = importId();
    insertAdyen(imp, "t1", LocalDate.of(2026, 1, 1));
    insertAdyen(imp, "t2", LocalDate.of(2026, 2, 1));

    writeTools.reattributeTransaction(
        List.of(new TxReference("t1", 0), new TxReference("t2", 0)), "Fizz Media");
    long merchant = merchantId();
    long contractId =
        ((Number)
                db.fetchValue(
                    "SELECT id FROM contracts WHERE counterparty_id = ? AND mandate_id = 'attributed'",
                    merchant))
            .longValue();

    // Clear: evidence disappears but the materialized rows persist (resolvers never delete).
    writeTools.reattributeTransaction(
        List.of(new TxReference("t1", 0), new TxReference("t2", 0)), null);
    assertThat(readTools.counterpartyTransactions(merchant, null, null, null)).isEmpty();

    // Bare counterparty dismiss leaves the 'attributed' contract open (Major 1).
    writeTools.dismissCounterparty(merchant, null, null, null, "cleared", null);
    String statusAfterBare =
        db.select(CONTRACTS.STATUS).from(CONTRACTS).where(CONTRACTS.ID.eq(contractId))
            .fetchOne(CONTRACTS.STATUS);
    assertThat(statusAfterBare).isEqualTo("open");

    // Contract-targeted dismiss actually tears it down.
    writeTools.dismissCounterparty(merchant, contractId, null, null, "cleared", null);
    String statusAfterContract =
        db.select(CONTRACTS.STATUS).from(CONTRACTS).where(CONTRACTS.ID.eq(contractId))
            .fetchOne(CONTRACTS.STATUS);
    assertThat(statusAfterContract).isEqualTo("dismissed");

    boolean inUnmatched =
        readTools.listUnmatchedRecurring(null, null).stream()
            .anyMatch(e -> e.contractId() != null && e.contractId() == contractId);
    assertThat(inUnmatched).isFalse();
  }
}
