package de.visterion.aletheia.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Regression test for a prod misconfiguration: {@code application.yml}'s {@code app:}/{@code ro:}
 * datasource blocks were nested under {@code aletheia.ingest} instead of {@code
 * aletheia.datasource}, so the env vars documented for {@link AppDataSourceProperties} and {@link
 * RoDataSourceProperties} (spec §6/§7) never reached those {@code @ConfigurationProperties}
 * beans. In prod this meant {@code sql_query}'s {@code roDsl} silently fell back to {@code
 * spring.datasource.*} (the superuser role) instead of the intended read-only {@code aletheia_ro}.
 *
 * <p>Deliberately does NOT set {@code aletheia.datasource.ro.username} directly -- an inlined test
 * property at that exact key would bind correctly today regardless of the YAML nesting bug and
 * prove nothing. Instead it sets the env var placeholders ({@code ALETHEIA_RO_DB_USER}/{@code
 * ALETHEIA_APP_DB_USER}) that {@code application.yml} itself references, the same way prod does,
 * so the test actually exercises the YAML wiring.
 */
@SpringBootTest(
    properties = {
      "ALETHEIA_RO_DB_USER=distinctive-ro-test-user",
      "ALETHEIA_APP_DB_USER=distinctive-app-test-user"
    })
class DataSourcePropertiesBindingIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add(
        "aletheia.ingest.dir", () -> "target/test-no-ingest-dir-datasource-properties-binding-it");
  }

  @Autowired private RoDataSourceProperties roDataSourceProperties;
  @Autowired private AppDataSourceProperties appDataSourceProperties;

  @Test
  void roDbUserEnvVarBindsToDatasourceRoUsername() {
    assertThat(roDataSourceProperties.getUsername()).isEqualTo("distinctive-ro-test-user");
  }

  @Test
  void appDbUserEnvVarBindsToDatasourceAppUsername() {
    assertThat(appDataSourceProperties.getUsername()).isEqualTo("distinctive-app-test-user");
  }
}
