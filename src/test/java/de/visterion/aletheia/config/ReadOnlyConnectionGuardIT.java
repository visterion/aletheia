package de.visterion.aletheia.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises all three branches of {@link ReadOnlyConnectionGuard#verify(DSLContext)} directly, on
 * hand-built connections -- separate from the Spring-wired {@code roDsl} bean covered by {@code
 * SqlQueryReadOnlySessionIT}.
 */
class ReadOnlyConnectionGuardIT extends AbstractPostgresIT {

  private static final String RESTRICTED_USER = "readonly_guard_test";
  private static final String RESTRICTED_PASSWORD = "readonly_guard_pw";

  @Autowired private DSLContext db;

  @BeforeEach
  void provisionRestrictedRole() {
    Boolean roleExists =
        db.fetchOne("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)", RESTRICTED_USER)
            .get(0, Boolean.class);
    if (Boolean.TRUE.equals(roleExists)) {
      db.execute("DROP OWNED BY " + RESTRICTED_USER + " CASCADE");
      db.execute("DROP ROLE " + RESTRICTED_USER);
    }
    db.execute("CREATE ROLE " + RESTRICTED_USER + " LOGIN PASSWORD '" + RESTRICTED_PASSWORD + "'");
    db.execute("GRANT USAGE ON SCHEMA public TO " + RESTRICTED_USER);
    db.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + RESTRICTED_USER);
  }

  @AfterAll
  static void dropRestrictedRole() throws Exception {
    // A hand-built connection, not the autowired (instance-scoped) db: @AfterAll is static.
    try (Connection connection =
            DriverManager.getConnection(containerJdbcUrl(), containerUsername(), containerPassword());
        var statement = connection.createStatement()) {
      var rows =
          statement.executeQuery(
              "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RESTRICTED_USER + "')");
      rows.next();
      if (rows.getBoolean(1)) {
        statement.execute("DROP OWNED BY " + RESTRICTED_USER + " CASCADE");
        statement.execute("DROP ROLE " + RESTRICTED_USER);
      }
    }
  }

  @Test
  void safeWhenSessionIsReadOnlyEvenUnderAFullPrivilegeRole() throws Exception {
    // A single hand-held connection: SET persists only for its own session, so the SET and both
    // verify() queries must run on the very same physical connection.
    try (Connection connection =
        DriverManager.getConnection(containerJdbcUrl(), containerUsername(), containerPassword())) {
      connection.createStatement().execute("SET default_transaction_read_only = on");
      DSLContext sessionReadOnlyDsl = DSL.using(connection, SQLDialect.POSTGRES);

      assertThatCode(() -> ReadOnlyConnectionGuard.verify(sessionReadOnlyDsl))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void safeWhenTheRoleCannotInsert() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(containerJdbcUrl());
    ds.setUsername(RESTRICTED_USER);
    ds.setPassword(RESTRICTED_PASSWORD);
    DSLContext restrictedDsl = DSL.using((DataSource) ds, SQLDialect.POSTGRES);

    assertThatCode(() -> ReadOnlyConnectionGuard.verify(restrictedDsl))
        .doesNotThrowAnyException();
  }

  @Test
  void throwsWhenNeitherSessionNorRoleIsRestricted() {
    // db is the full-privilege, non-read-only app connection (default session state).
    assertThatThrownBy(() -> ReadOnlyConnectionGuard.verify(db))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neither session-read-only")
        .hasMessageContaining("can INSERT into transactions");
  }
}
