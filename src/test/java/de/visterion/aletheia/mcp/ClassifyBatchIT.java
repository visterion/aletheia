package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ClassifyBatchIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired CounterpartyResolver resolver;
  @Autowired WriteTools writeTools;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long seedUntaggedCounterparty(String namePrefix) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "name")
        .set(COUNTERPARTIES.IDENTITY_VALUE, namePrefix + "-" + UUID.randomUUID())
        .set(COUNTERPARTIES.DISPLAY_NAME, namePrefix)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private List<Long> seedUntaggedCounterparties(int count, String namePrefix) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(i -> seedUntaggedCounterparty(namePrefix + i))
        .toList();
  }

  @Test
  void tagsAllSelectedCounterparties() {
    seedUntaggedCounterparties(3, "untagged");

    var ack =
        writeTools.classifyCounterparty(
            null,
            new CounterpartySelector(true, null, null, null),
            List.of(new TagInput("domain", "insurance")),
            TagSource.auto,
            null,
            false);

    assertThat(ack.affectedCount()).isEqualTo(3);
    assertThat(ack.dimensions()).containsExactly("domain");
    assertThat(db.fetchCount(COUNTERPARTY_TAGS, COUNTERPARTY_TAGS.VALUE.eq("insurance"))).isEqualTo(3);
  }

  @Test
  void over200RequiresConfirm() {
    seedUntaggedCounterparties(201, "many");
    List<TagInput> tags = List.of(new TagInput("domain", "insurance"));

    assertThatThrownBy(
            () ->
                writeTools.classifyCounterparty(
                    null,
                    new CounterpartySelector(true, null, null, null),
                    tags,
                    TagSource.auto,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("201");

    var ack =
        writeTools.classifyCounterparty(
            null,
            new CounterpartySelector(true, null, null, null),
            tags,
            TagSource.auto,
            null,
            true);

    assertThat(ack.affectedCount()).isEqualTo(201);
  }

  @Test
  void over1000IsRejectedEvenWithConfirm() {
    seedUntaggedCounterparties(1001, "huge");
    List<TagInput> tags = List.of(new TagInput("domain", "insurance"));

    assertThatThrownBy(
            () ->
                writeTools.classifyCounterparty(
                    null,
                    new CounterpartySelector(true, null, null, null),
                    tags,
                    TagSource.auto,
                    null,
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1001");
  }

  @Test
  void explicitIdsAlsoHitCaps() {
    List<Long> ids = seedUntaggedCounterparties(201, "explicit");
    List<TagInput> tags = List.of(new TagInput("domain", "insurance"));

    assertThatThrownBy(
            () -> writeTools.classifyCounterparty(ids, null, tags, TagSource.auto, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("201");
  }

  @Test
  void rollbackOnMidBatchFailure() {
    long validId = seedUntaggedCounterparty("valid");
    List<TagInput> tags = List.of(new TagInput("domain", "insurance"));

    assertThatThrownBy(
            () ->
                writeTools.classifyCounterparty(
                    List.of(validId, 999_999L), null, tags, TagSource.auto, null, false))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(db.fetchCount(COUNTERPARTY_TAGS)).isZero();
  }

  @Test
  void duplicateExplicitIdsAreDeduped() {
    long id = seedUntaggedCounterparty("duplicate");
    List<TagInput> tags = List.of(new TagInput("domain", "insurance"));

    var ack =
        writeTools.classifyCounterparty(
            List.of(id, id), null, tags, TagSource.auto, null, false);

    assertThat(ack.affectedCount()).isEqualTo(1);
    assertThat(
            db.fetchCount(
                COUNTERPARTY_HISTORY,
                COUNTERPARTY_HISTORY.COUNTERPARTY_ID.eq(id).and(COUNTERPARTY_HISTORY.FIELD.eq("tag:domain"))))
        .isEqualTo(1);
  }

  @Test
  void emptyTagsListChangesNothing() {
    long id = seedUntaggedCounterparty("no-tags");

    var ack =
        writeTools.classifyCounterparty(List.of(id), null, List.of(), TagSource.auto, null, false);

    assertThat(ack.affectedCount()).isZero();
    assertThat(ack.dimensions()).isEmpty();
    assertThat(db.fetchCount(COUNTERPARTY_TAGS)).isZero();
    assertThat(db.fetchCount(COUNTERPARTY_HISTORY)).isZero();
  }
}
