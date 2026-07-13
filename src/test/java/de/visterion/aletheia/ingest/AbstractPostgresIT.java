package de.visterion.aletheia.ingest;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Boots the full context against a Postgres container with the startup ingest disabled. */
@SpringBootTest
@Testcontainers
abstract class AbstractPostgresIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    // Point ingest at a non-existent dir so the startup runner no-ops (spec §7 test isolation).
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir");
  }
}
