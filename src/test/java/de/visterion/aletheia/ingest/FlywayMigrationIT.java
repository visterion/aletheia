package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FlywayMigrationIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  @Test
  void migratesEmptyDatabaseAndIsRepeatable() throws Exception {
    Flyway flyway = Flyway.configure().dataSource(dataSource()).load();
    flyway.migrate();

    // Re-running migrate is a no-op (no pending migrations).
    var result = Flyway.configure().dataSource(dataSource()).load().migrate();
    assertThat(result.migrationsExecuted).isZero();

    try (Connection c = dataSource().getConnection();
        ResultSet rs =
            c.getMetaData().getTables(null, "public", null, new String[] {"TABLE"})) {
      var tables = new java.util.ArrayList<String>();
      while (rs.next()) {
        tables.add(rs.getString("TABLE_NAME"));
      }
      assertThat(tables)
          .contains(
              "imports",
              "transactions",
              "counterparties",
              "counterparty_tags",
              "recurring",
              "contracts",
              "counterparty_history");
    }
  }
}
