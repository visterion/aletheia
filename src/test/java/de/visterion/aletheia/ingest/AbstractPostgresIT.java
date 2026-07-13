package de.visterion.aletheia.ingest;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full context against a shared Postgres container with the startup ingest disabled.
 *
 * <p>The container uses the Testcontainers singleton pattern (started once in a static initializer,
 * never stopped between classes) instead of the {@code @Testcontainers}/{@code @Container} per-class
 * lifecycle. Multiple subclasses share one cached Spring context via {@code @ServiceConnection}; a
 * per-class lifecycle would stop and restart the container on a new port between classes while the
 * cached context's connection pool still targeted the old port, causing "connection refused" in
 * whichever subclass ran second.
 */
@SpringBootTest
public abstract class AbstractPostgresIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    // Point ingest at a non-existent dir so the startup runner no-ops (spec §7 test isolation).
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir");
  }

  @DynamicPropertySource
  static void pointBothDataSourcesAtTheSharedContainer(DynamicPropertyRegistry registry) {
    // Prod has three DB roles (owner/app/ro, spec §6/§7); tests run single-role, so both the
    // app and read-only datasource beans point at the same Testcontainers instance here.
    registry.add("aletheia.datasource.app.url", POSTGRES::getJdbcUrl);
    registry.add("aletheia.datasource.app.username", POSTGRES::getUsername);
    registry.add("aletheia.datasource.app.password", POSTGRES::getPassword);
    registry.add("aletheia.datasource.ro.url", POSTGRES::getJdbcUrl);
    registry.add("aletheia.datasource.ro.username", POSTGRES::getUsername);
    registry.add("aletheia.datasource.ro.password", POSTGRES::getPassword);
    // spring.flyway.url/user/password (owner creds in prod, spec §6/§7) has hard-coded
    // localhost defaults in application.yml; point Flyway at the same shared container.
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }
}
