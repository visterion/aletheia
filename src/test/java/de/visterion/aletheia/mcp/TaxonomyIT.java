package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TaxonomyIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired ReadTools readTools;

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE counterparty_tags, counterparties RESTART IDENTITY CASCADE");
  }

  private long insertCounterparty(String identityValue, String displayName) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "creditor_id")
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, displayName)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void insertTag(long counterpartyId, String dimension, String value) {
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, counterpartyId)
        .set(COUNTERPARTY_TAGS.DIMENSION, dimension)
        .set(COUNTERPARTY_TAGS.VALUE, value)
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .execute();
  }

  @Test
  void surfacesEmergentTagVocabularyPerDimensionOrderedByCountDesc() {
    long insurer1 = insertCounterparty("CDTR-INS-1", "Insurer One");
    long insurer2 = insertCounterparty("CDTR-INS-2", "Insurer Two");
    long telco = insertCounterparty("CDTR-TEL-1", "Telco One");
    long essentialOne = insertCounterparty("CDTR-ESS-1", "Essential One");

    insertTag(insurer1, "domain", "insurance");
    insertTag(insurer2, "domain", "insurance");
    insertTag(telco, "domain", "telco");
    insertTag(essentialOne, "necessity", "essential");

    List<TaxonomyDimension> taxonomy = readTools.taxonomy();

    assertThat(taxonomy).extracting(TaxonomyDimension::dimension).contains("domain", "necessity");

    TaxonomyDimension domain =
        taxonomy.stream().filter(d -> d.dimension().equals("domain")).findFirst().orElseThrow();
    assertThat(domain.values())
        .extracting(TaxonomyValue::value, TaxonomyValue::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("insurance", 2L),
            org.assertj.core.groups.Tuple.tuple("telco", 1L));

    TaxonomyDimension necessity =
        taxonomy.stream()
            .filter(d -> d.dimension().equals("necessity"))
            .findFirst()
            .orElseThrow();
    assertThat(necessity.values())
        .extracting(TaxonomyValue::value, TaxonomyValue::count)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("essential", 1L));

    long nonEmptyGroups = taxonomy.stream().filter(d -> !d.values().isEmpty()).count();
    assertThat(nonEmptyGroups).isEqualTo(2);
  }
}
