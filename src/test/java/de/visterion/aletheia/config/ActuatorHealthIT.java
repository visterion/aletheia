package de.visterion.aletheia.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-actuator-health-it");
  }

  @LocalServerPort private int port;

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

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
