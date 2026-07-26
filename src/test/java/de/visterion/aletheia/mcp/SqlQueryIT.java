package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Pins the {@code sql_query} statement gate after it was widened to accept a leading {@code WITH}.
 *
 * <p>The widened regex admits statements that write -- both a write inside a CTE and a top-level
 * write with a CTE prefix -- so the SELECT-only database role is the only thing standing between
 * the LLM and a mutation. {@code AbstractPostgresIT} points BOTH datasources at the same
 * full-privilege container user ("tests run single-role"), so a naive version of the write test
 * would not fail -- it would execute the DELETE and report green. This class therefore provisions
 * its own restricted role and drives {@link ReadTools} through it.
 */
class SqlQueryIT extends AbstractPostgresIT {

  private static final String RO_USER = "sqlquery_ro_test";
  private static final String RO_PASSWORD = "sqlquery_ro_pw";

  @Autowired private DSLContext db;
  @Autowired private ReadTools readTools;

  private ReadTools restrictedReadTools;

  @BeforeEach
  void provisionReadOnlyRole() {
    // Re-entrant: DROP OWNED BY errors if the role does not exist yet (e.g. the first run of this
    // class), so only run the cleanup pair when a previous run left the role behind.
    Boolean roleExists =
        db.fetchOne("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)", RO_USER)
            .get(0, Boolean.class);
    if (Boolean.TRUE.equals(roleExists)) {
      db.execute("DROP OWNED BY " + RO_USER + " CASCADE");
      db.execute("DROP ROLE " + RO_USER);
    }
    // LOGIN needs a password: the postgres:16-alpine image configures scram-sha-256 for host
    // connections and refuses a passwordless role over TCP.
    db.execute("CREATE ROLE " + RO_USER + " LOGIN PASSWORD '" + RO_PASSWORD + "'");
    db.execute("GRANT USAGE ON SCHEMA public TO " + RO_USER);
    db.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + RO_USER);

    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(containerJdbcUrl());
    ds.setUsername(RO_USER);
    ds.setPassword(RO_PASSWORD);
    DSLContext restrictedRo = DSL.using((DataSource) ds, SQLDialect.POSTGRES);

    // Constructed by hand, NOT taken from the context: the Spring-managed roDsl bean points at the
    // superuser. Running the SQL directly on restrictedRo would skip requireSelectOnly and prove
    // nothing about the tool.
    restrictedReadTools = new ReadTools(db, restrictedRo, null, null, null);

    db.execute(
        "INSERT INTO counterparties (identity_type, identity_value, display_name) "
            + "VALUES ('creditor_id', 'SQLQUERY-IT-BACKSTOP', 'Test Counterparty') "
            + "ON CONFLICT (identity_type, identity_value) DO NOTHING");
  }

  @AfterAll
  static void dropReadOnlyRole() throws java.sql.SQLException {
    // A hand-built connection, not the autowired (instance-scoped) db: @AfterAll is static.
    try (Connection connection =
            DriverManager.getConnection(containerJdbcUrl(), containerUsername(), containerPassword());
        var statement = connection.createStatement()) {
      var rows =
          statement.executeQuery(
              "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RO_USER + "')");
      rows.next();
      if (rows.getBoolean(1)) {
        statement.execute("DROP OWNED BY " + RO_USER + " CASCADE");
        statement.execute("DROP ROLE " + RO_USER);
      }
    }
  }

  @Test
  void aLeadingWithIsAccepted() {
    assertThatCode(
            () ->
                readTools.sqlQuery(
                    "WITH c AS (SELECT count(*) AS n FROM counterparties) SELECT n FROM c"))
        .doesNotThrowAnyException();
    assertThatCode(() -> readTools.sqlQuery("  with c as (select 1 as n) select n from c"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                readTools.sqlQuery(
                    "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 3)"
                        + " SELECT n FROM t"))
        .doesNotThrowAnyException();
  }

  @Test
  void stackedStatementsAndSelectIntoAreStillRejectedBeforeTheDatabase() {
    assertThatThrownBy(() -> readTools.sqlQuery("WITH c AS (SELECT 1) SELECT 1; SELECT 2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> readTools.sqlQuery("SELECT * INTO copy_of FROM counterparties"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aWriteInsideACteIsStoppedByTheRoleAndChangesNothing() {
    long before = (Long) db.fetchValue("SELECT count(*) FROM counterparties");
    // A vacuous backstop (0 == 0) would pass even if the DELETE executed; the @BeforeEach seed
    // guarantees there is at least one row for the DELETE to actually remove.
    assertThat(before).isGreaterThan(0L);

    // isInstanceOf + hasMessageContaining, not just "not an IllegalArgumentException": a typo'd
    // table, a missing grant on an unrelated object, or a dropped connection would also satisfy
    // the weaker check without proving the role actually blocked the write.
    assertThatThrownBy(
            () ->
                restrictedReadTools.sqlQuery(
                    "WITH x AS (DELETE FROM counterparties RETURNING *) SELECT count(*) FROM x"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("permission denied");

    // An exception alone would not prove nothing was written.
    assertThat((Long) db.fetchValue("SELECT count(*) FROM counterparties")).isEqualTo(before);
  }

  @Test
  void aTopLevelWriteWithACtePrefixIsStoppedByTheRoleAndChangesNothing() {
    // The widened regex admits this shape too: it starts with WITH, has no semicolon, and carries
    // no INTO token for the SELECT_INTO guard to catch.
    long before = (Long) db.fetchValue("SELECT count(*) FROM counterparties");
    assertThat(before).isGreaterThan(0L);

    assertThatThrownBy(
            () ->
                restrictedReadTools.sqlQuery("WITH x AS (SELECT 1) DELETE FROM counterparties"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("permission denied");

    assertThat((Long) db.fetchValue("SELECT count(*) FROM counterparties")).isEqualTo(before);
  }
}
