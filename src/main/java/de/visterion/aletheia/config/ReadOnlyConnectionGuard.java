package de.visterion.aletheia.config;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fails startup closed if the {@code roDsl} connection backing {@code sql_query} could actually
 * perform a write.
 *
 * <p>The regex in {@code ReadTools#requireSelectOnly} now admits a leading {@code WITH}, so {@code
 * WITH x AS (SELECT 1) DELETE FROM t} passes the tool-layer guard; the only thing that can still
 * stop it is the connection itself. {@link DataSourceConfig#roDataSource} sets {@code
 * default_transaction_read_only = on} via {@code connectionInitSql} so Postgres refuses any DML
 * (SQLSTATE {@code 25006}) regardless of what the {@code aletheia_ro} role happens to be granted --
 * which, until the role split (Task 8, {@code application.yml} §"app:/ro:"), is the same
 * full-privilege role as the app datasource.
 *
 * <p>This runner re-verifies that property is actually in effect at startup rather than trusting
 * the wiring blindly: either the session is read-only, or (a defense that predates the read-only
 * session and remains valid on its own) the role cannot write. Only when <em>neither</em> holds is
 * {@code sql_query} actually able to write, and only then does this throw. It deliberately does
 * NOT fail just because the role has write privileges -- that is true in prod today, and asserting
 * it would stop the container from booting on every future deploy for a condition the session-level
 * read-only setting already neutralizes.
 *
 * <p>A connectivity failure while probing {@code roDsl} (unreachable host, bad credentials, ...)
 * is deliberately NOT treated as "unprotected": {@code ActuatorHealthIT} pins the invariant that
 * the ro datasource must never be able to take the whole container down on its own -- only
 * {@code sql_query} depends on it. A connection that cannot be reached also cannot execute a
 * write, so this logs a warning and lets startup proceed rather than failing closed on a problem
 * that is not the one this guard exists to catch.
 */
@Component
@Order(0)
public class ReadOnlyConnectionGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ReadOnlyConnectionGuard.class);

  private final DSLContext roDsl;

  public ReadOnlyConnectionGuard(@Qualifier("roDsl") DSLContext roDsl) {
    this.roDsl = roDsl;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      verify(roDsl);
    } catch (DataAccessException e) {
      log.warn(
          "Could not verify roDsl is read-only (connection problem, not a security finding): {}."
              + " sql_query will simply fail until connectivity is restored; startup proceeds"
              + " because the ro datasource must never be able to take the container down on its"
              + " own.",
          e.getMessage());
      return;
    }
    log.info("roDsl connection verified read-only (sql_query cannot write).");
  }

  /**
   * Package-private so {@code ReadOnlyConnectionGuardIT} can exercise all three branches directly
   * against hand-built connections, without booting the full {@link ApplicationRunner}.
   */
  static void verify(DSLContext roDsl) {
    Object readOnlySetting =
        roDsl.fetchValue("SELECT current_setting('default_transaction_read_only')");
    if ("on".equals(readOnlySetting)) {
      return;
    }
    Object canInsert =
        roDsl.fetchValue("SELECT has_table_privilege(current_user, 'transactions', 'INSERT')");
    if (Boolean.FALSE.equals(canInsert)) {
      return;
    }
    Object currentUser = roDsl.fetchValue("SELECT current_user");
    throw new IllegalStateException(
        "roDsl is connected as '"
            + currentUser
            + "', which is neither session-read-only (default_transaction_read_only=on) nor"
            + " restricted to SELECT (it can INSERT into transactions): sql_query would be able"
            + " to write. Set aletheia.datasource.ro.* to a read-only role, or check the"
            + " connectionInitSql wiring on the ro datasource.");
  }
}
