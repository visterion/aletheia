package de.visterion.aletheia.tagrules;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.time.LocalDate;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TagRuleResolverIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired TagRuleResolver resolver;
  @Autowired de.visterion.aletheia.substrate.CounterpartyResolver counterpartyResolver;

  @BeforeEach
  void clean() {
    truncateAll();
  }

  // Truncate again after the last test so leftover rows (esp. tag_rules) don't leak into other
  // IT classes sharing the singleton Testcontainers Postgres instance (AbstractPostgresIT).
  @AfterEach
  void cleanUp() {
    truncateAll();
  }

  private void truncateAll() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "transactions, tag_rules, counterparties RESTART IDENTITY CASCADE");
  }

  // --- helpers -------------------------------------------------------------
  private void insertBooking(String hash, String name, String remittance, String direction) {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, amount, "
            + "currency, direction, booking_date, booking_status, counterparty_name, "
            + "remittance_info, raw) "
            + "VALUES (?, 0, 9.99, 'EUR', ?, ?, 'BOOK', ?, ?, '{}'::jsonb)",
        hash, direction, LocalDate.of(2026, 8, 1), name, remittance);
  }

  private long counterpartyIdByName(String name) {
    // identity for a name booking = upper(trim(collapse-ws(NFC(name))))
    String iv = name.trim().replaceAll("\\s+", " ").toUpperCase();
    return (Long)
        db.fetchValue(
            "SELECT id FROM counterparties WHERE identity_type='name' AND identity_value=?", iv);
  }

  private long insertRule(String conditionsJson, String actionsJson) {
    return (Long)
        db.fetchValue(
            "INSERT INTO tag_rules (name, conditions, actions) VALUES ('r', ?::jsonb, ?::jsonb) RETURNING id",
            conditionsJson, actionsJson);
  }

  private List<String> tagsOf(long cpId, String dimension) {
    return db.fetch(
            "SELECT value FROM counterparty_tags WHERE counterparty_id=? AND dimension=? ORDER BY value",
            cpId, dimension)
        .map(r -> r.get("value", String.class));
  }

  private String sourceOf(long cpId, String dimension) {
    return (String)
        db.fetchValue(
            "SELECT source FROM counterparty_tags WHERE counterparty_id=? AND dimension=?",
            cpId, dimension);
  }

  // --- tests ---------------------------------------------------------------

  @Test
  void containsMatchTagsTheCounterparty() {
    insertBooking("h1", "ACME", "TELEKOM MONTHLY", "DBIT");
    // resolve counterparties first
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).containsExactly("telekommunikation");
    assertThat(sourceOf(cp, "domain")).isEqualTo("confirmed");
  }

  @Test
  void nonMatchingBookingIsUntouched() {
    insertBooking("h1", "ACME", "GROCERIES", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).isEmpty();
  }

  @Test
  void andConditionsMustBeSatisfiedByTheSameBooking() {
    // booking A matches direction, booking B matches remittance, but neither matches both
    insertBooking("h1", "ACME", "GROCERIES", "DBIT");
    insertBooking("h2", "ACME", "TELEKOM", "CRDT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"},"
            + "{\"field\":\"direction\",\"op\":\"equals\",\"value\":\"DBIT\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).isEmpty();
  }

  @Test
  void overwritesAutoButSkipsConfirmed() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    db.execute(
        "INSERT INTO counterparty_tags (counterparty_id, dimension, value, source) "
            + "VALUES (?, 'domain', 'handel', 'auto'), (?, 'nature', 'variabel', 'confirmed')",
        cp, cp);
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"},"
            + "{\"dimension\":\"nature\",\"value\":\"fixkosten\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).containsExactly("telekommunikation"); // auto overwritten
    assertThat(sourceOf(cp, "domain")).isEqualTo("confirmed");
    assertThat(tagsOf(cp, "nature")).containsExactly("variabel"); // confirmed untouched
    assertThat(sourceOf(cp, "nature")).isEqualTo("confirmed");
  }

  @Test
  void twoValuesInSameDimensionBothWritten() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"a\"},{\"dimension\":\"domain\",\"value\":\"b\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).containsExactly("a", "b");
  }

  @Test
  void lowerIdRuleWinsThenHigherSkips() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"first\"}]");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"second\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).containsExactly("first");
  }

  @Test
  void idempotentAcrossTwoPasses() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();
    int historyAfterFirst =
        (Integer)
            db.fetchValue(
                "SELECT count(*)::int FROM counterparty_history WHERE counterparty_id=?", cp);
    resolver.resolve();
    int historyAfterSecond =
        (Integer)
            db.fetchValue(
                "SELECT count(*)::int FROM counterparty_history WHERE counterparty_id=?", cp);

    assertThat(tagsOf(cp, "domain")).containsExactly("telekommunikation");
    assertThat(historyAfterSecond).isEqualTo(historyAfterFirst);
  }

  @Test
  void dismissedCounterpartyIsNotTagged() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    db.execute("UPDATE counterparties SET status='dismissed' WHERE id=?", cp);
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).isEmpty();
  }

  @Test
  void reviewedAndStatusUntouched() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();

    Boolean reviewed = (Boolean) db.fetchValue("SELECT reviewed FROM counterparties WHERE id=?", cp);
    String status = (String) db.fetchValue("SELECT status FROM counterparties WHERE id=?", cp);
    assertThat(reviewed).isFalse();
    assertThat(status).isEqualTo("open");
  }

  @Test
  void oneBadRuleIsSkippedAndTheRestApply() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    // rule 1: structurally-valid actions/conditions arrays whose element fails Java parse
    // (unknown enum field), so it must be skipped without aborting the rest.
    db.execute(
        "INSERT INTO tag_rules (name, conditions, actions) VALUES "
            + "('bad', '[{\"field\":\"not_a_field\",\"op\":\"equals\",\"value\":\"x\"}]'::jsonb, "
            + "'[{\"dimension\":\"domain\",\"value\":\"x\"}]'::jsonb)");
    insertRule(
        "[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve(); // must not throw

    assertThat(tagsOf(cp, "domain")).containsExactly("telekommunikation");
  }

  @Test
  void midApplyFailureRollsBackTheWholeRule() {
    insertBooking("h1", "ACME", "TELEKOM", "DBIT");
    resolveCounterparties();
    long cp = counterpartyIdByName("ACME");
    db.execute(
        "INSERT INTO counterparty_tags (counterparty_id, dimension, value, source) "
            + "VALUES (?, 'domain', 'handel', 'auto')",
        cp);
    // Bypasses Java validation (which the resolver never re-runs): the actions array is
    // structurally valid (satisfies V14's non-empty-array CHECK) and parses cleanly into
    // List<RuleAction> since RuleAction.dimension is a plain String. The first dimension
    // ("domain") is valid and applies first -- delete+insert -- then the second dimension
    // ("bogus") fails the counterparty_tags CHECK (dimension IN ('domain','nature','necessity'))
    // mid-rule, inside the same TransactionTemplate, proving the delete+insert for "domain"
    // gets rolled back too.
    db.execute(
        "INSERT INTO tag_rules (name, conditions, actions) VALUES "
            + "('mid-apply-bad', "
            + "'[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]'::jsonb, "
            + "'[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"},"
            + "{\"dimension\":\"bogus\",\"value\":\"x\"}]'::jsonb)");

    resolver.resolve(); // must not throw -- the bad rule is caught and logged

    assertThat(tagsOf(cp, "domain")).containsExactly("handel");
    assertThat(sourceOf(cp, "domain")).isEqualTo("auto");
    int historyCount =
        (Integer)
            db.fetchValue(
                "SELECT count(*)::int FROM counterparty_history WHERE counterparty_id=?", cp);
    assertThat(historyCount).isZero();
  }

  @Test
  void creditorIdEqualsMatch() {
    db.execute(
        "INSERT INTO transactions (content_hash, occurrence_index, amount, "
            + "currency, direction, booking_date, booking_status, creditor_id, raw) "
            + "VALUES ('h1', 0, 9.99, 'EUR', 'DBIT', ?, 'BOOK', 'CID123', '{}'::jsonb)",
        LocalDate.of(2026, 8, 1));
    resolveCounterparties();
    long cp =
        (Long)
            db.fetchValue(
                "SELECT id FROM counterparties WHERE identity_type='creditor_id' AND identity_value='CID123'");
    insertRule(
        "[{\"field\":\"creditor_id\",\"op\":\"equals\",\"value\":\"CID123\"}]",
        "[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]");

    resolver.resolve();

    assertThat(tagsOf(cp, "domain")).containsExactly("telekommunikation");
  }

  /** Runs the counterparty resolver so identities exist before applying rules. */
  private void resolveCounterparties() {
    counterpartyResolver.resolve();
  }
}
