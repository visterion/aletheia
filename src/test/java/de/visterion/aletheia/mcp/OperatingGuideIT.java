package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperatingGuideIT extends AbstractPostgresIT {

  @Autowired OperatingGuideService service;
  @Autowired DSLContext db;

  @BeforeEach
  void resetPreferences() {
    // The 'default' row is a process-wide singleton (seeded once by Flyway, shared across all
    // test classes on the singleton Postgres container). A @BeforeEach reset guarantees every
    // test in this class starts from a known clean state regardless of what other test classes
    // (e.g. ToolScopeEnforcementIT) did to the row before Surefire picked this class to run.
    resetPreferencesRow();
  }

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
    // operating_guide is NOT truncated: the seeded 'default' row must survive. Reset its
    // mutable preferences columns so a preference-mutating test can't poison another test under
    // any ordering.
    resetPreferencesRow();
  }

  private void resetPreferencesRow() {
    db.execute(
        "UPDATE operating_guide SET preferences_md='', preferences_updated_at=NULL, "
            + "preferences_updated_by=NULL WHERE scope='default'");
  }

  @Test
  void emptyDbSnapshotHasNoNulls() { // P1 (M3)
    String md = service.wakeUp();
    assertThat(md).contains("(none recorded yet)"); // empty preferences
    assertThat(md).contains("(no imports yet)");
    assertThat(md).doesNotContain("null");
  }

  @Test
  void snapshotCountsAreCorrect() {
    // 2 unreviewed counterparties, 1 open contract
    long a = seedCp(false);
    seedCp(false);
    db.execute(
        "INSERT INTO contracts (counterparty_id, mandate_id, source, status) "
            + "VALUES (?, 'M1', 'auto', 'open')",
        a);
    String md = service.wakeUp();
    assertThat(md).contains("Unreviewed counterparties: 2");
    assertThat(md).contains("Open contracts awaiting confirmation: 1");
  }

  @Test
  void preferencesRoundTripAndReplace() {
    service.updatePreferences("- Beihilfe civil servant: medical reconciliation matters", "tester");
    assertThat(service.wakeUp()).contains("Beihilfe civil servant");
    service.updatePreferences("- Hide payment services", "tester");
    String md = service.wakeUp();
    assertThat(md).contains("Hide payment services");
    assertThat(md).doesNotContain("Beihilfe civil servant"); // full replace, not append
    assertThat(
            db.fetchValue("SELECT preferences_updated_by FROM operating_guide WHERE scope='default'"))
        .isEqualTo("tester");
  }

  @Test
  void workflowGuideIsNeverTouchedByPreferenceUpdate() {
    String before =
        (String) db.fetchValue("SELECT workflow_md FROM operating_guide WHERE scope='default'");
    service.updatePreferences("- anything", "tester");
    String after =
        (String) db.fetchValue("SELECT workflow_md FROM operating_guide WHERE scope='default'");
    assertThat(after).isEqualTo(before);
  }

  @Test
  void updateThrowsWhenDefaultRowMissing() { // m5
    // Snapshot the seeded row so it can be restored: the @AfterEach deliberately does not
    // truncate operating_guide, so other tests rely on the 'default' row surviving.
    var backup =
        db.fetchOne("SELECT workflow_md, preferences_md FROM operating_guide WHERE scope='default'");
    db.execute("DELETE FROM operating_guide WHERE scope='default'");
    try {
      assertThatThrownBy(() -> service.updatePreferences("x", "tester"))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      db.execute(
          "INSERT INTO operating_guide (scope, workflow_md, preferences_md) VALUES ('default', ?, ?)",
          backup.get("workflow_md", String.class),
          backup.get("preferences_md", String.class));
    }
  }

  @Test
  void wakeUpLeadsWithLiveStateAndNoLongerCarriesTheGuide() {
    String md = service.wakeUp();

    assertThat(md).doesNotContain("How to work with Aletheia");
    assertThat(md.indexOf("# Aletheia — state")).isZero();
    assertThat(md.indexOf("# Aletheia — state")).isLessThan(md.indexOf("# Customer preferences"));
    assertThat(md).contains("Operating guide: read_me()");
  }

  @Test
  void readMeReturnsTheSeededGuideVerbatim() {
    assertThat(service.operatingGuide())
        .isEqualTo(
            (String)
                db.fetchValue(
                    "SELECT workflow_md FROM operating_guide WHERE scope='default'"));
  }

  @Test
  void aDuplicatedCustomerPreferencesHeadingIsStrippedOnce() {
    service.updatePreferences("# Customer preferences\n## Household\n- something", "test");

    String md = service.wakeUp();

    assertThat(md.split("# Customer preferences", -1)).hasSize(2); // exactly one occurrence
    assertThat(md).contains("## Household");
  }

  @Test
  void aDuplicatedHeadingAfterALeadingBlankLineIsStrippedToo() {
    // An LLM writing preferences with a leading newline is plausible; the strip must look past
    // blank lines to find the heading, not just at literal line zero.
    service.updatePreferences("\n# Customer preferences\n- body", "test");

    String md = service.wakeUp();

    assertThat(md.split("# Customer preferences", -1)).hasSize(2); // exactly one occurrence
    assertThat(md).contains("- body");
  }

  @Test
  void wakeUpStaysUnderTheLineBudget() {
    String md = service.wakeUp(); // empty preferences (reset by @BeforeEach)
    assertThat(md.lines().count()).isLessThanOrEqualTo(20);
  }

  @Test
  void aCustomerHeadingThatIsNotTheDuplicateSurvives() {
    // A blanket "strip any leading H1" rule would silently delete the customer's own heading and
    // leave only its body -- data loss for the sake of one saved string comparison.
    service.updatePreferences("# Wichtig: Konto X nie anfassen\n- detail", "test");

    assertThat(service.wakeUp()).contains("# Wichtig: Konto X nie anfassen");
  }

  @Test
  void preferencesThatAreNothingButTheStrippedHeadingFallBackToThePlaceholder() {
    // The blank check must run AFTER the strip, or this leaves an empty section.
    service.updatePreferences("# Customer preferences\n\n", "test");

    assertThat(service.wakeUp()).contains("(none recorded yet)");
  }

  private long seedCp(boolean reviewed) {
    return (long)
        db.fetchValue(
            "INSERT INTO counterparties (identity_type, identity_value, display_name, reviewed, status) "
                // Upper-cased: V18's counterparties_name_identity_normalized pins 'name'
                // identities to the identity normal form, and gen_random_uuid() is lower-case hex.
                + "VALUES ('name', upper('cp-' || gen_random_uuid()), 'cp', ?, 'open') "
                + "RETURNING id",
            reviewed);
  }
}
