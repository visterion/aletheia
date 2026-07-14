package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Verifies the V7 backfill re-normalizes {@code display_name} on rows that were already resolved
 * by an earlier version of {@link CounterpartyResolver} (padded, pre-collapse). Uses its own
 * container and a two-phase programmatic Flyway migration (target=V6, then latest) since it needs
 * to seed data in the pre-V7 schema state; it does not extend {@code AbstractPostgresIT}.
 */
class V7BackfillIT {

  @Test
  void v7CollapsesExistingPaddedNames() {
    try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
      pg.start();
      var flywayV6 =
          Flyway.configure()
              .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .locations("classpath:db/migration")
              .target(MigrationVersion.fromVersion("6"))
              .load();
      flywayV6.migrate();
      var dsl = DSL.using(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      dsl.execute(
          "INSERT INTO counterparties(identity_type, identity_value, display_name) "
              + "VALUES ('name','X','Foo   Bar    Baz')");
      var flywayV7 =
          Flyway.configure()
              .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .locations("classpath:db/migration")
              .load(); // no target -> up to latest (V7+)
      flywayV7.migrate();
      String name =
          dsl.select(field("display_name"))
              .from(table("counterparties"))
              .where(field("identity_value").eq("X"))
              .fetchOne(0, String.class);
      assertThat(name).isEqualTo("Foo Bar Baz");
    }
  }
}
