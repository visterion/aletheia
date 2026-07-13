package de.visterion.aletheia.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Verifies the two-datasource wiring from spec §6/§7: a {@code @Primary} app {@link DSLContext}
 * and a named {@code roDsl} {@link DSLContext} both exist and can run a SELECT.
 *
 * <p>Grant enforcement (the read-only role actually being unable to write) is a prod-only,
 * DB-role concern (spec §7) and is not exercised here; tests run single-role, so both beans
 * point at the same Testcontainers instance (see {@link AbstractPostgresIT}).
 */
class ReadOnlyDataSourceIT extends AbstractPostgresIT {

  @Autowired private DSLContext dslContext;

  @Autowired
  @Qualifier("roDsl")
  private DSLContext roDsl;

  @Test
  void appAndReadOnlyDslContextsAreBothWired() {
    assertThat(dslContext).isNotNull();
    assertThat(roDsl).isNotNull();
  }

  @Test
  void roDslCanRunASelect() {
    Record row = roDsl.fetchOne("select 1 as x");

    assertThat(row).isNotNull();
    assertThat(row.get("x")).isEqualTo(1);
  }
}
