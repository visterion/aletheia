package de.visterion.aletheia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Auth failures on /ingest are logged (WARN) with the reason but never the presented token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class AuthFailureLoggingIT {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void disableStartupIngest(DynamicPropertyRegistry registry) {
    registry.add("aletheia.ingest.dir", () -> "target/test-no-ingest-dir-authlog-it");
  }

  @LocalServerPort private int port;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void badTokenLogsAuthFailureWithoutToken(CapturedOutput output) throws Exception {
    String secret = "super-secret-bearer-value-12345";
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ingest"))
            .header("Authorization", "Bearer " + secret)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("[]"))
            .build();

    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(output.getOut()).contains("auth failed");
    assertThat(output.getOut()).contains("/ingest");
    assertThat(output.getOut()).doesNotContain(secret);
  }
}
