package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Unit test for the startup-resilience fix (final-review Minor #1): a transient failure inside
 * {@link CounterpartyResolver#resolve()} must not fail application boot when it happens via
 * {@link CounterpartyResolver#run(org.springframework.boot.ApplicationArguments)}, but must still
 * propagate when {@code resolve()} is called directly (e.g. from the ingest endpoint path).
 *
 * <p>Uses a jOOQ {@link MockDataProvider} (already on the classpath via {@code
 * spring-boot-starter-jooq} -- no new test dependency) that fails every statement, instead of
 * Mockito, which is not a project test dependency.
 */
class CounterpartyResolverTest {

  private static DSLContext failingDb() {
    MockDataProvider failing =
        ctx -> {
          throw new SQLException("simulated transient failure");
        };
    return DSL.using(new MockConnection(failing), SQLDialect.POSTGRES);
  }

  @Test
  void resolveThrowsWhenDatabaseFails() {
    CounterpartyResolver resolver = new CounterpartyResolver(failingDb());

    assertThatThrownBy(resolver::resolve).isInstanceOf(RuntimeException.class);
  }

  @Test
  void runSwallowsAndLogsWhenDatabaseFails() {
    CounterpartyResolver resolver = new CounterpartyResolver(failingDb());

    assertThatCode(() -> resolver.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }
}
