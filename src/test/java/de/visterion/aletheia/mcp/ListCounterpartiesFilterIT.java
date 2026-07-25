package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Coverage for the {@code list_counterparties} namePattern/limit parameters (spec §8a). */
class ListCounterpartiesFilterIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired ReadTools readTools;

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE counterparties RESTART IDENTITY CASCADE");
  }

  private long insertCounterparty(String identityValue, String displayName, String override) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "creditor_id")
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, displayName)
        .set(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE, override)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  /**
   * The tripwire for the aliased-field trap: DISPLAY_NAME_EFFECTIVE carries .as("display_name"),
   * so reusing it in a WHERE clause would match the raw column and ignore the override.
   */
  @Test
  void namePatternMatchesTheOverrideAndNotTheRawDisplayName() {
    long overridden = insertCounterparty("CDTR-RAW-1", "ZQP Mktp DE X7", "Acme");

    List<CounterpartySummary> byOverride = readTools.listCounterparties(null, null, "acme", null);
    assertThat(byOverride).extracting(CounterpartySummary::id).containsExactly(overridden);

    List<CounterpartySummary> byRawName = readTools.listCounterparties(null, null, "zqp", null);
    assertThat(byRawName).isEmpty();
  }

  @Test
  void namePatternIsCaseInsensitiveAndSubstringBased() {
    long id = insertCounterparty("CDTR-TEL-1", "Telco One GmbH", null);

    assertThat(readTools.listCounterparties(null, null, "TELCO", null))
        .extracting(CounterpartySummary::id)
        .containsExactly(id);
    assertThat(readTools.listCounterparties(null, null, "co one", null))
        .extracting(CounterpartySummary::id)
        .containsExactly(id);
  }

  @Test
  void namePatternTreatsUnderscoreAndPercentAsLikeWildcards() {
    long id = insertCounterparty("CDTR-WILD-1", "Telco One GmbH", null);

    assertThat(readTools.listCounterparties(null, null, "Telco%GmbH", null))
        .extracting(CounterpartySummary::id)
        .containsExactly(id);
    assertThat(readTools.listCounterparties(null, null, "T_lco", null))
        .extracting(CounterpartySummary::id)
        .containsExactly(id);
  }

  @Test
  void blankNamePatternBehavesAsOmitted() {
    insertCounterparty("CDTR-A", "Alpha", null);
    insertCounterparty("CDTR-B", "Beta", null);

    assertThat(readTools.listCounterparties(null, null, "", null)).hasSize(2);
    assertThat(readTools.listCounterparties(null, null, "   ", null)).hasSize(2);
  }

  @Test
  void namePatternDoesNotResurrectMergedCounterparties() {
    long target = insertCounterparty("CDTR-KEEP", "Telco One", null);
    long folded = insertCounterparty("CDTR-FOLD", "Telco One Variant", null);
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.MERGED_INTO, target)
        .where(COUNTERPARTIES.ID.eq(folded))
        .execute();

    assertThat(readTools.listCounterparties(null, null, "telco", null))
        .extracting(CounterpartySummary::id)
        .containsExactly(target);
    assertThat(readTools.listCounterparties(CounterpartyFilter.unreviewed, null, "telco", null))
        .extracting(CounterpartySummary::id)
        .containsExactly(target);
  }

  @Test
  void limitCapsTheResultAndRejectsNonPositiveValues() {
    insertCounterparty("CDTR-A", "Alpha", null);
    insertCounterparty("CDTR-B", "Beta", null);
    insertCounterparty("CDTR-C", "Gamma", null);

    assertThat(readTools.listCounterparties(null, null, null, 2)).hasSize(2);
    assertThat(readTools.listCounterparties(null, null, null, 99)).hasSize(3);

    assertThatThrownBy(() -> readTools.listCounterparties(null, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> readTools.listCounterparties(null, null, null, -5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
