package de.visterion.aletheia.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Verifies the actuator health endpoint that the Docker {@code HEALTHCHECK} and prod liveness
 * probing rely on.
 *
 * <p>Two properties are pinned here on purpose:
 *
 * <ul>
 *   <li>{@code /actuator/health} answers 200 {@code {"status":"UP"}} <em>without</em> a bearer
 *       token — {@link de.visterion.aletheia.auth.AuthFilter} guards only {@code /mcp} and {@code
 *       /ingest}, and a healthcheck cannot carry credentials.
 *   <li>the body carries no component details ({@code management.endpoint.health.show-details:
 *       never}) — the app is publicly reachable via Cloudflare, so this unauthenticated endpoint
 *       must leak nothing about the datasource or any other component.
 *   <li>the {@code db} contributor covers the <em>primary</em> datasource only: this context wires
 *       {@code aletheia.datasource.ro.*} to an unreachable port, and health must still be UP. The
 *       RO role backs only the {@code sql_query} tool, so it must not be probed every 30s by the
 *       Docker healthcheck, nor be able to take the container down on its own.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-actuator-health-it");
    // Deliberately broken read-only datasource: port 1 accepts nothing. Nothing but sql_query
    // touches it, so a healthy app must not report DOWN because of it.
    registry.add("aletheia.datasource.ro.url", () -> "jdbc:postgresql://127.0.0.1:1/unreachable");
    registry.add("aletheia.datasource.ro.username", () -> "nobody");
    registry.add("aletheia.datasource.ro.password", () -> "nothing");
  }

  @LocalServerPort private int port;
  @Autowired private ApplicationContext context;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void healthEndpointIsUpAndReachableWithoutAToken() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void healthEndpointExposesNoComponentDetails() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.body()).doesNotContain("components").doesNotContain("\"db\"");
  }

  @Test
  void databaseHealthCoversOnlyThePrimaryDatasource() throws Exception {
    // Behavioural: the unreachable RO datasource must not drag the aggregate status down.
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");

    // Structural: exactly one db contributor, and it is bound to the primary datasource -- Boot's
    // multi-datasource composite auto-configuration has backed off.
    assertThat(context.getBeanNamesForType(HealthIndicator.class))
        .filteredOn(name -> name.startsWith("db"))
        .containsExactly("dbHealthIndicator");
    assertThat(context.containsBean("dbHealthContributor")).isFalse();
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
