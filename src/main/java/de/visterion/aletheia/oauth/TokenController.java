package de.visterion.aletheia.oauth;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth 2.0 token endpoint. Supports two grant types: {@code authorization_code} (with PKCE)
 * and {@code refresh_token}.
 *
 * <p>Form-urlencoded request body per RFC 6749 §3.2.
 */
@RestController
public class TokenController {

  private final OAuthProperties props;
  private final TokenEndpointService service;

  public TokenController(OAuthProperties props, TokenEndpointService service) {
    this.props = props;
    this.service = service;
  }

  @PostMapping(
      value = "/oauth/token",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> token(
      @RequestParam("grant_type") String grantType,
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "redirect_uri", required = false) String redirectUri,
      @RequestParam(value = "code_verifier", required = false) String codeVerifier,
      @RequestParam(value = "refresh_token", required = false) String refreshToken) {
    if (!props.isEnabled()) return ResponseEntity.notFound().build();
    return switch (grantType) {
      case "authorization_code" -> handleAuthorizationCode(code, clientId, redirectUri, codeVerifier);
      case "refresh_token" -> handleRefresh(refreshToken, clientId);
      default -> ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "unsupported_grant_type",
                  "error_description",
                  "Supported: authorization_code, refresh_token"));
    };
  }

  private ResponseEntity<?> handleAuthorizationCode(
      String code, String clientId, String redirectUri, String codeVerifier) {
    if (code == null || clientId == null || redirectUri == null || codeVerifier == null) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "invalid_request",
                  "error_description",
                  "code, client_id, redirect_uri, code_verifier required"));
    }
    TokenEndpointService.Result result = service.exchangeCode(code, clientId, redirectUri, codeVerifier);
    return toResponse(result);
  }

  private ResponseEntity<?> handleRefresh(String refreshToken, String clientId) {
    if (refreshToken == null || clientId == null) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error", "invalid_request", "error_description", "refresh_token, client_id required"));
    }
    TokenEndpointService.Result result = service.refresh(refreshToken, clientId);
    return toResponse(result);
  }

  private static ResponseEntity<?> toResponse(TokenEndpointService.Result result) {
    return switch (result) {
      case TokenEndpointService.Result.Ok ok -> ResponseEntity.ok(ok.response());
      case TokenEndpointService.Result.Err err -> ResponseEntity.badRequest()
          .body(Map.of("error", err.error(), "error_description", err.description()));
    };
  }
}
