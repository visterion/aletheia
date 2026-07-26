package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the actual production vulnerability, not a hand-built stand-in for it.
 *
 * <p>{@code AbstractPostgresIT} points {@code aletheia.datasource.app.*} and {@code
 * aletheia.datasource.ro.*} at the SAME full-privilege Testcontainers role -- exactly the
 * situation verified against prod: {@code aletheia.datasource.ro.*} defaults to empty, so {@code
 * DataSourceConfig.resolve} falls back to the owner/app role. Unlike {@link SqlQueryIT} (which
 * hand-provisions its own SELECT-only role to test the tool-layer regex and role-based denial),
 * this class drives the REAL Spring-wired {@code roDsl}/{@link ReadTools} bean, so the only thing
 * that can stop a {@code WITH}-prefixed write here is the session-level {@code
 * default_transaction_read_only} setting from {@code DataSourceConfig.roDataSource}'s {@code
 * connectionInitSql}.
 *
 * <p>Before that wiring existed, this test's write assertion failed: the DELETE executed (0 rows
 * seeded means "0 == 0" trivially, which is why a row is seeded first and {@code before > 0} is
 * asserted).
 */
class SqlQueryReadOnlySessionIT extends AbstractPostgresIT {

  @Autowired private DSLContext db;
  @Autowired private ReadTools readTools;

  @BeforeEach
  void seedOneCounterparty() {
    db.execute(
        "INSERT INTO counterparties (identity_type, identity_value, display_name) "
            + "VALUES ('creditor_id', 'SQLQUERY-RO-SESSION-IT', 'Test Counterparty') "
            + "ON CONFLICT (identity_type, identity_value) DO NOTHING");
  }

  @Test
  void aTopLevelWriteWithACtePrefixIsStoppedByTheSessionEvenUnderAFullPrivilegeRole() {
    long before = (Long) db.fetchValue("SELECT count(*) FROM counterparties");
    assertThat(before).isGreaterThan(0L);

    assertThatThrownBy(
            () ->
                readTools.sqlQuery("WITH x AS (SELECT 1) DELETE FROM counterparties"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("read-only transaction")
        .satisfies(
            thrown -> {
              Throwable cause = thrown.getCause();
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getSQLState()).isEqualTo("25006");
            });

    assertThat((Long) db.fetchValue("SELECT count(*) FROM counterparties")).isEqualTo(before);
  }
}
