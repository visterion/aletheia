package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SelectorTagStatusIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartySelectorResolver resolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
  }

  private long seedCp(String name) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "name")
        .set(COUNTERPARTIES.IDENTITY_VALUE, name + "-" + UUID.randomUUID())
        .set(COUNTERPARTIES.DISPLAY_NAME, name)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void tag(long cpId, String dimension, String value) {
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, cpId)
        .set(COUNTERPARTY_TAGS.DIMENSION, dimension)
        .set(COUNTERPARTY_TAGS.VALUE, value)
        .set(COUNTERPARTY_TAGS.SOURCE, "auto")
        .execute();
  }

  private void addContract(long cpId) {
    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, cpId)
        .set(CONTRACTS.MANDATE_ID, "M-" + cpId)
        .set(CONTRACTS.SOURCE, "auto")
        .set(CONTRACTS.STATUS, "open")
        .execute();
  }

  private CounterpartySelector where(
      List<String> domainIn, List<String> natureIn, Boolean reviewed, Boolean hasContract) {
    return new CounterpartySelector(null, null, null, null, domainIn, natureIn, reviewed, hasContract);
  }

  @Test
  void domainInMatchesOnlyDomainDimension() {
    long a = seedCp("a"); tag(a, "domain", "transfer-privat");
    long b = seedCp("b"); tag(b, "domain", "handel");
    long c = seedCp("c"); tag(c, "nature", "transfer-privat"); // wrong dimension
    assertThat(resolver.resolve(where(List.of("transfer-privat"), null, null, null)))
        .containsExactly(a);
  }

  @Test
  void natureInMatchesOnlyNatureDimension() {
    long a = seedCp("a"); tag(a, "nature", "zahlungsdienst");
    long b = seedCp("b"); tag(b, "domain", "zahlungsdienst"); // wrong dimension (Finding 1)
    assertThat(resolver.resolve(where(null, List.of("zahlungsdienst"), null, null)))
        .containsExactly(a);
  }

  @Test
  void reviewedFilters() {
    long r = seedCp("r"); db.update(COUNTERPARTIES).set(COUNTERPARTIES.REVIEWED, true)
        .where(COUNTERPARTIES.ID.eq(r)).execute();
    long u = seedCp("u"); // reviewed defaults false
    assertThat(resolver.resolve(where(null, null, false, null))).containsExactly(u);
    assertThat(resolver.resolve(where(null, null, true, null))).containsExactly(r);
  }

  @Test
  void hasContractFilters() {
    long withC = seedCp("withC"); addContract(withC);
    long withoutC = seedCp("withoutC");
    assertThat(resolver.resolve(where(null, null, null, false))).containsExactly(withoutC);
    assertThat(resolver.resolve(where(null, null, null, true))).containsExactly(withC);
  }

  @Test
  void emptyListRejected() {
    assertThatThrownBy(() -> resolver.resolve(where(List.of(), null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.resolve(where(null, List.of(), null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void combinationResolvesSweepSet() {
    long hit = seedCp("hit"); tag(hit, "domain", "transfer-privat"); // reviewed=false, no contract
    long reviewed = seedCp("rev"); tag(reviewed, "domain", "transfer-privat");
    db.update(COUNTERPARTIES).set(COUNTERPARTIES.REVIEWED, true)
        .where(COUNTERPARTIES.ID.eq(reviewed)).execute();
    long contracted = seedCp("con"); tag(contracted, "domain", "transfer-privat"); addContract(contracted);
    assertThat(resolver.resolve(where(List.of("transfer-privat"), null, false, false)))
        .containsExactly(hit);
  }
}
