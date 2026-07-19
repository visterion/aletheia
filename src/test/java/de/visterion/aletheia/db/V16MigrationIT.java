package de.visterion.aletheia.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class V16MigrationIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  // statusCheckAcceptsEndedAndRejectsGarbage seeds counterparties + contracts into the shared
  // Testcontainers Postgres; truncate them so this class does not pollute later ITs (the container
  // is reused across test classes via Spring context caching, and class order differs local vs CI).
  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE counterparties RESTART IDENTITY CASCADE");
  }

  @Test
  void addsDisplayNameOverrideAndEndDateColumns() {
    Integer overrideCols =
        db.fetchCount(
            db.selectFrom("information_schema.columns")
                .where("table_name = 'counterparties'")
                .and("column_name = 'display_name_override'"));
    assertThat(overrideCols).isEqualTo(1);

    Integer endDateCols =
        db.fetchCount(
            db.selectFrom("information_schema.columns")
                .where("table_name = 'contracts'")
                .and("column_name = 'end_date'"));
    assertThat(endDateCols).isEqualTo(1);
  }

  @Test
  void statusCheckAcceptsEndedAndRejectsGarbage() {
    // seed a counterparty + contract, then flip status to 'ended' -- must succeed
    long cpId =
        db.insertInto(DSL.table("counterparties"))
            .columns(
                DSL.field("identity_type"), DSL.field("identity_value"), DSL.field("display_name"))
            .values("name", "V16-A", "V16 A")
            .returning(DSL.field("id"))
            .fetchOne()
            .get("id", Long.class);
    long contractId =
        db.insertInto(DSL.table("contracts"))
            .columns(DSL.field("counterparty_id"), DSL.field("status"), DSL.field("source"))
            .values(cpId, "confirmed", "confirmed")
            .returning(DSL.field("id"))
            .fetchOne()
            .get("id", Long.class);

    int updated =
        db.update(DSL.table("contracts"))
            .set(DSL.field("status"), "ended")
            .set(DSL.field("end_date"), java.time.LocalDate.of(2026, 7, 19))
            .where("id = " + contractId)
            .execute();
    assertThat(updated).isEqualTo(1);

    assertThatThrownBy(
            () ->
                db.update(DSL.table("contracts"))
                    .set(DSL.field("status"), "bogus")
                    .where("id = " + contractId)
                    .execute())
        .isInstanceOf(DataAccessException.class);
  }
}
