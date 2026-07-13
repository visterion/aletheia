package de.visterion.aletheia.oauth;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 7591 Dynamic Client Registration. MCP clients (Claude.ai, ChatGPT) call this once when the
 * user adds Aletheia as a Custom Connector to obtain a {@code client_id}. Public-clients-only:
 * {@code token_endpoint_auth_method} must be {@code none} and PKCE is required at /authorize.
 */
@RestController
public class ClientRegistrationController {

  private final OAuthProperties props;
  private final ClientRegistrationService service;

  public ClientRegistrationController(OAuthProperties props, ClientRegistrationService service) {
    this.props = props;
    this.service = service;
  }

  @PostMapping(
      value = "/oauth/register",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> register(@RequestBody ClientRegistrationRequest req) {
    if (!props.isEnabled()) return ResponseEntity.notFound().build();
    if (!props.isDynamicClientRegistrationEnabled()) {
      return ResponseEntity.status(403)
          .body(
              Map.of(
                  "error",
                  "registration_disabled",
                  "error_description",
                  "Dynamic Client Registration is disabled on this server"));
    }
    ClientRegistrationService.Result result = service.register(req);
    return switch (result) {
      case ClientRegistrationService.Result.Ok ok -> ResponseEntity.status(201).body(ok.response());
      case ClientRegistrationService.Result.Err err -> ResponseEntity.badRequest()
          .body(Map.of("error", err.error(), "error_description", err.description()));
    };
  }
}
