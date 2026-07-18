package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import de.visterion.aletheia.tagrules.RuleAction;
import de.visterion.aletheia.tagrules.RuleCondition;
import de.visterion.aletheia.tagrules.RuleField;
import de.visterion.aletheia.tagrules.RuleOp;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TagRuleToolsIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;
  @Autowired CounterpartyResolver counterpartyResolver;

  private static final List<RuleCondition> COND =
      List.of(new RuleCondition(RuleField.remittance_info, RuleOp.contains, "telekom"));
  private static final List<RuleAction> ACT = List.of(new RuleAction("domain", "telekommunikation"));

  @BeforeEach
  void seed() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "transactions, tag_rules, counterparties, imports RESTART IDENTITY CASCADE");
    long importId =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "synthetic.json")
            .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne()
            .getId();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "h1")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 8, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "ACME")
        .set(TRANSACTIONS.REMITTANCE_INFO, "TELEKOM")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
    counterpartyResolver.resolve();
  }

  @AfterEach
  void cleanUp() {
    // Runs on a shared Testcontainers Postgres instance (AbstractPostgresIT): leaving rows
    // behind would poison a later-running test class (e.g. OperatingGuideIT's counterparty
    // counts) regardless of ordering.
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "transactions, tag_rules, counterparties, imports RESTART IDENTITY CASCADE");
  }

  private long cpId() {
    return (Long) db.fetchValue("SELECT id FROM counterparties WHERE identity_value='ACME'");
  }

  @Test
  void dryRunWritesNothingButReportsMatch() {
    CreateTagRuleAck ack = writeTools.createTagRule("telekom", COND, ACT, true, false, false);

    assertThat(ack.ruleId()).isNull();
    assertThat(ack.matchCount()).isEqualTo(1);
    assertThat(ack.wouldChangeCount()).isEqualTo(1);
    assertThat(db.fetchCount(db.selectFrom("tag_rules"))).isZero();
    assertThat(db.fetchCount(db.selectFrom("counterparty_tags"))).isZero();
  }

  @Test
  void createWithBackfillTagsExisting() {
    CreateTagRuleAck ack = writeTools.createTagRule("telekom", COND, ACT, false, true, false);

    assertThat(ack.ruleId()).isNotNull();
    assertThat(ack.appliedCount()).isEqualTo(1);
    assertThat(db.fetchValue(
            "SELECT value FROM counterparty_tags WHERE counterparty_id=? AND dimension='domain'", cpId()))
        .isEqualTo("telekommunikation");
  }

  @Test
  void createWithoutBackfillTagsNothingNow() {
    CreateTagRuleAck ack = writeTools.createTagRule("telekom", COND, ACT, false, false, false);

    assertThat(ack.ruleId()).isNotNull();
    assertThat(ack.appliedCount()).isZero();
    assertThat(db.fetchCount(db.selectFrom("counterparty_tags"))).isZero();
  }

  @Test
  void listAndDisableAndDelete() {
    long id = writeTools.createTagRule("telekom", COND, ACT, false, false, false).ruleId();

    List<TagRuleView> rules = readTools.listTagRules();
    assertThat(rules).hasSize(1);
    assertThat(rules.get(0).enabled()).isTrue();

    writeTools.setTagRuleEnabled(id, false);
    assertThat((Boolean) db.fetchValue("SELECT enabled FROM tag_rules WHERE id=?", id)).isFalse();

    writeTools.deleteTagRule(id);
    assertThat(db.fetchCount(db.selectFrom("tag_rules"))).isZero();
  }

  @Test
  void unknownRuleIdThrows() {
    assertThatThrownBy(() -> writeTools.setTagRuleEnabled(999L, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> writeTools.deleteTagRule(999L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidRuleThrowsAndPersistsNothing() {
    assertThatThrownBy(
            () ->
                writeTools.createTagRule(
                    "bad",
                    List.of(new RuleCondition(RuleField.creditor_id, RuleOp.contains, "x")),
                    ACT,
                    false,
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.fetchCount(db.selectFrom("tag_rules"))).isZero();
  }
}
