package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.mcp.Cashflow.CashflowParams;
import de.visterion.aletheia.mcp.Cashflow.InvestmentMode;
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

class CashflowServiceIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired CashflowService cashflowService;

  private static final String RAW = "{}";
  private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
  private static final LocalDate TO = LocalDate.of(2026, 6, 30);

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

  private void insertTxn(long imp, String hash, LocalDate date, String amount, String dir,
      String creditorId, String name) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, date)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, dir)
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private void insertTag(long cpId, String dim, String value, String source) {
    db.insertInto(
            org.jooq.impl.DSL.table("counterparty_tags"),
            org.jooq.impl.DSL.field("counterparty_id"),
            org.jooq.impl.DSL.field("dimension"),
            org.jooq.impl.DSL.field("value"),
            org.jooq.impl.DSL.field("source"))
        .values(cpId, dim, value, source)
        .execute();
  }

  private long cpId(String identityValue) {
    return db.select(COUNTERPARTIES.ID).from(COUNTERPARTIES)
        .where(COUNTERPARTIES.IDENTITY_VALUE.eq(identityValue)).fetchOne(COUNTERPARTIES.ID);
  }

  private static CashflowParams defaults() {
    return new CashflowParams(FROM, TO,
        List.of("income_source", "domain", "counterparty"), true, true, InvestmentMode.AS_SAVING, 6,
        BigDecimal.ZERO);
  }

  @Test
  void endToEndMonthBalancesWithIncomeExpenseAndDepot() {
    long imp = importId();
    insertTxn(imp, "h-sal", LocalDate.of(2026, 6, 1), "3000.00", "CRDT", "CDTR-EMP", "Employer");
    insertTxn(imp, "h-food", LocalDate.of(2026, 6, 5), "400.00", "DBIT", "CDTR-REWE", "Rewe");
    insertTxn(imp, "h-etf", LocalDate.of(2026, 6, 10), "500.00", "DBIT", "CDTR-BROKER", "Broker");
    resolver.run(null);
    insertTag(cpId("CDTR-EMP"), "domain", "einkommen", "confirmed");
    insertTag(cpId("CDTR-REWE"), "domain", "lebensmittel", "confirmed");
    insertTag(cpId("CDTR-BROKER"), "nature", "investment", "confirmed");

    var cf = cashflowService.cashflow(defaults());

    assertThat(cf.meta().income()).isEqualByComparingTo("3000.00");
    assertThat(cf.meta().outflow()).isEqualByComparingTo("400.00");
    assertThat(cf.meta().saving()).isEqualByComparingTo("500.00"); // ETF buy as saving
    assertThat(cf.meta().saldo()).isEqualByComparingTo("2100.00");
    assertThat(cf.nodes()).anySatisfy(n -> {
      assertThat(n.id()).isEqualTo("domain:lebensmittel");
      assertThat(n.value()).isEqualByComparingTo("400.00");
    });
  }

  @Test
  void multiTagCounterpartyUsesConfirmedBucket() {
    long imp = importId();
    insertTxn(imp, "h-sal", LocalDate.of(2026, 6, 1), "1000.00", "CRDT", "CDTR-EMP", "Employer");
    insertTxn(imp, "h-x", LocalDate.of(2026, 6, 5), "50.00", "DBIT", "CDTR-X", "X Co");
    resolver.run(null);
    insertTag(cpId("CDTR-EMP"), "domain", "einkommen", "confirmed");
    insertTag(cpId("CDTR-X"), "domain", "handel", "auto");
    insertTag(cpId("CDTR-X"), "domain", "lebensmittel", "confirmed"); // confirmed wins
    var cf = cashflowService.cashflow(defaults());
    assertThat(cf.nodes()).anySatisfy(n -> {
      assertThat(n.id()).isEqualTo("domain:lebensmittel");
      assertThat(n.value()).isEqualByComparingTo("50.00");
    });
    assertThat(cf.nodes()).noneSatisfy(n -> assertThat(n.id()).isEqualTo("domain:handel"));
  }

  @Test
  void emptyPeriodYieldsEmptyGraph() {
    var cf = cashflowService.cashflow(defaults());
    assertThat(cf.nodes()).isEmpty();
    assertThat(cf.links()).isEmpty();
    assertThat(cf.meta().saldo()).isEqualByComparingTo("0.00");
  }

  @Test
  void splitParentsExcludedFromCashflow() {
    long imp = importId();
    insertTxn(imp, "h-sal", LocalDate.of(2026, 6, 1), "1000.00", "CRDT", "CDTR-EMP", "Employer");
    String parent = "parent-cf-001";
    insertTxn(imp, parent, LocalDate.of(2026, 6, 5), "100.00", "DBIT", "CDTR-REWE", "Rewe");
    // one child keeps the merchant, one is bargeld
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-a").set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null).set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 6, 5))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("60.00")).set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT").set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "Rewe").set(TRANSACTIONS.CREDITOR_ID, "CDTR-REWE")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parent)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0).execute();
    resolver.run(null);
    insertTag(cpId("CDTR-EMP"), "domain", "einkommen", "confirmed");
    insertTag(cpId("CDTR-REWE"), "domain", "lebensmittel", "confirmed");

    var cf = cashflowService.cashflow(defaults());
    // only the 60 child counts, not the 100 parent
    assertThat(cf.nodes()).anySatisfy(n -> {
      assertThat(n.id()).isEqualTo("domain:lebensmittel");
      assertThat(n.value()).isEqualByComparingTo("60.00");
    });
  }
}
