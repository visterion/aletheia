package de.visterion.aletheia.mcp;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReattributeRuleFollowupIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired CounterpartyResolver counterpartyResolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "transactions, tag_rules, counterparties RESTART IDENTITY CASCADE");
  }

  @BeforeEach
  void seed() {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "h1")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 8, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "ADYEN")
        .set(TRANSACTIONS.REMITTANCE_INFO, "STREAMFLIX SUB")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
    counterpartyResolver.resolve();
    db.execute(
        "INSERT INTO tag_rules (name, conditions, actions) VALUES ('streamflix', "
            + "'[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"streamflix\"}]'::jsonb, "
            + "'[{\"dimension\":\"domain\",\"value\":\"unterhaltung\"}]'::jsonb)");
  }

  @Test
  void reattributionTagsNewlyMintedMerchantViaRule() {
    writeTools.reattributeTransaction(List.of(new TxReference("h1", 0)), "StreamFlix");

    long attributed =
        (long) db.fetchValue("SELECT id FROM counterparties WHERE identity_value='STREAMFLIX'");
    assertThat(
            db.fetchValue(
                "SELECT value FROM counterparty_tags WHERE counterparty_id=? AND dimension='domain'",
                attributed))
        .isEqualTo("unterhaltung");
  }
}
