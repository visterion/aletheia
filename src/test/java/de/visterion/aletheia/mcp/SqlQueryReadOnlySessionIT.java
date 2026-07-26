package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
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
 * that can stop a write here is {@link ReadTools#sqlQuery}'s own per-call {@code SET TRANSACTION
 * READ ONLY} transaction -- neither the session-level {@code default_transaction_read_only}
 * default nor the DB role's grants (both defense in depth only) are what is under test.
 *
 * <p>Before the connection-level wiring existed, {@link
 * #aTopLevelWriteWithACtePrefixIsStoppedEvenUnderAFullPrivilegeRole} failed: the DELETE executed
 * (0 rows seeded means "0 == 0" trivially, which is why a row is seeded first and {@code before >
 * 0} is asserted). After the session-level fix landed but before the per-call transaction fix,
 * {@link #aSetConfigCallCannotDisableReadOnlyForALaterCall} failed the same way: {@code
 * default_transaction_read_only} is {@code USERSET}, so a first {@code sql_query} call could flip
 * it back off on the pooled connection (which {@code connectionInitSql} only sets once, at
 * connection creation) and a second call's DELETE would then execute.
 */
class SqlQueryReadOnlySessionIT extends AbstractPostgresIT {

  private static final String SEED_IDENTITY_VALUE = "SQLQUERY-RO-SESSION-IT";

  @Autowired private DSLContext db;
  @Autowired private ReadTools readTools;

  @BeforeEach
  void seedOneCounterparty() {
    db.execute(
        "INSERT INTO counterparties (identity_type, identity_value, display_name) "
            + "VALUES ('creditor_id', ?, 'Test Counterparty') "
            + "ON CONFLICT (identity_type, identity_value) DO NOTHING",
        SEED_IDENTITY_VALUE);
  }

  @AfterEach
  void removeSeedRow() {
    // AbstractPostgresIT's container is a shared singleton across the whole test JVM run; an
    // un-cleaned seed row would leak into every other test class that scans counterparties.
    db.execute(
        "DELETE FROM counterparties WHERE identity_type = 'creditor_id' AND identity_value = ?",
        SEED_IDENTITY_VALUE);
  }

  @Test
  void aTopLevelWriteWithACtePrefixIsStoppedEvenUnderAFullPrivilegeRole() {
    long before = (Long) db.fetchValue("SELECT count(*) FROM counterparties");
    assertThat(before).isGreaterThan(0L);

    assertThatThrownBy(
            () -> readTools.sqlQuery("WITH x AS (SELECT 1) DELETE FROM counterparties"))
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

  @Test
  void aSetConfigCallCannotDisableReadOnlyForALaterCall() {
    long before = (Long) db.fetchValue("SELECT count(*) FROM counterparties");
    assertThat(before).isGreaterThan(0L);

    // Call 1: a bare SELECT, single statement, no semicolon, no INTO -- passes requireSelectOnly
    // outright. set_config's session-level GUC would persist on the pooled connection until
    // Hikari retires it (connectionInitSql runs only once, at connection creation), if the
    // per-call transaction wrapper in sqlQuery did not close that off.
    assertThatCode(
            () ->
                readTools.sqlQuery(
                    "SELECT set_config('default_transaction_read_only', 'off', false)"))
        .doesNotThrowAnyException();

    // Call 2: the same CTE-prefixed write from the test above. If call 1 had actually disabled
    // read-only on this connection, this would now succeed and delete every row.
    assertThatThrownBy(
            () -> readTools.sqlQuery("WITH x AS (SELECT 1) DELETE FROM counterparties"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("read-only transaction");

    assertThat((Long) db.fetchValue("SELECT count(*) FROM counterparties")).isEqualTo(before);
  }
}
