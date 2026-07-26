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
 * Fails startup closed if neither of {@code roDsl}'s two defense-in-depth layers -- the
 * connection's session-level read-only default, or the DB role's grants -- is in effect.
 *
 * <p><b>This is not the security boundary and does not certify that {@code sql_query} cannot
 * write.</b> That property is enforced per call, inside {@code ReadTools#sqlQuery} itself, via an
 * explicit {@code SET TRANSACTION READ ONLY} transaction -- see {@code
 * SqlQueryReadOnlySessionIT}. This class only checks the two weaker, session/role-level defenses
 * that sit underneath that: {@link DataSourceConfig#roDataSource} sets {@code
 * default_transaction_read_only = on} via {@code connectionInitSql}, but that GUC is {@code
 * USERSET} -- a caller-controlled statement (e.g. {@code SELECT
 * set_config('default_transaction_read_only','off',false)}) can flip it back off on the pooled
 * connection until Hikari retires it, which is exactly why the per-transaction enforcement in
 * {@code sqlQuery} exists and does not rely on this setting holding.
 *
 * <p>This runner verifies at startup that at least one of the two defense-in-depth layers holds:
 * either the session-level GUC is currently {@code on}, or (independently) the role cannot {@code
 * INSERT} into {@code transactions}. Only when <em>neither</em> holds does this throw -- that
 * indicates the wiring is weaker than intended, even though {@code sqlQuery}'s own per-call
 * transaction still enforces the actual boundary regardless. It deliberately does NOT fail just
 * because the role has write privileges -- that is true in prod today.
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
    log.info(
        "roDsl connection-level defense-in-depth verified (session read-only or role"
            + " restricted); sql_query's own per-call SET TRANSACTION READ ONLY is the actual"
            + " write boundary.");
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
            + " restricted to SELECT (it can INSERT into transactions): both connection-level"
            + " defense-in-depth layers are absent. sql_query's own per-call SET TRANSACTION READ"
            + " ONLY still blocks writes, but this wiring is weaker than intended -- set"
            + " aletheia.datasource.ro.* to a read-only role, or check the connectionInitSql"
            + " wiring on the ro datasource.");
  }
}
