package de.visterion.aletheia.oauth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Implements RFC 7591 Dynamic Client Registration. Public-clients-only: {@code
 * token_endpoint_auth_method=none} is the only accepted method, and clients must use PKCE at the
 * authorization endpoint.
 */
@Service
public class ClientRegistrationService {

  private static final SecureRandom RNG = new SecureRandom();
  private static final List<String> ALLOWED_GRANT_TYPES = List.of("authorization_code", "refresh_token");
  private static final List<String> ALLOWED_RESPONSE_TYPES = List.of("code");

  private final OAuthRepository repo;

  public ClientRegistrationService(OAuthRepository repo) {
    this.repo = repo;
  }

  public Result register(ClientRegistrationRequest req) {
    if (req.redirectUris() == null || req.redirectUris().isEmpty()) {
      return Result.error("invalid_redirect_uri", "redirect_uris is required and must not be empty");
    }
    for (String uri : req.redirectUris()) {
      if (!RedirectUriValidator.isAcceptable(uri)) {
        return Result.error("invalid_redirect_uri", "redirect_uri not acceptable: " + uri);
      }
    }
    String authMethod = Optional.ofNullable(req.tokenEndpointAuthMethod()).orElse("none");
    if (!"none".equals(authMethod)) {
      return Result.error(
          "invalid_client_metadata",
          "Only public clients (token_endpoint_auth_method=none + PKCE) are supported");
    }
    List<String> grantTypes = Optional.ofNullable(req.grantTypes()).orElse(ALLOWED_GRANT_TYPES);
    for (String g : grantTypes) {
      if (!ALLOWED_GRANT_TYPES.contains(g)) {
        return Result.error("invalid_client_metadata", "Unsupported grant_type: " + g);
      }
    }
    List<String> responseTypes = Optional.ofNullable(req.responseTypes()).orElse(ALLOWED_RESPONSE_TYPES);
    for (String r : responseTypes) {
      if (!ALLOWED_RESPONSE_TYPES.contains(r)) {
        return Result.error("invalid_client_metadata", "Unsupported response_type: " + r);
      }
    }
    String clientId = generateClientId();
    String scope = Optional.ofNullable(req.scope()).orElse("read write");
    OAuthRepository.OAuthClient created =
        repo.insertClient(
            clientId,
            Optional.ofNullable(req.clientName()).orElse("unnamed-client"),
            req.redirectUris(),
            grantTypes,
            responseTypes,
            authMethod,
            scope,
            req.clientUri(),
            req.logoUri(),
            req.contacts(),
            req.softwareId(),
            req.softwareVersion());
    ClientRegistrationResponse response =
        new ClientRegistrationResponse(
            created.clientId(),
            created.createdAt().toEpochSecond(),
            created.clientName(),
            created.redirectUris(),
            created.grantTypes(),
            created.responseTypes(),
            created.tokenEndpointAuthMethod(),
            created.scope(),
            created.clientUri(),
            created.logoUri(),
            created.contacts(),
            created.softwareId(),
            created.softwareVersion());
    return Result.ok(response);
  }

  private static String generateClientId() {
    byte[] bytes = new byte[24];
    RNG.nextBytes(bytes);
    return "al_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public sealed interface Result permits Result.Ok, Result.Err {
    record Ok(ClientRegistrationResponse response) implements Result {}

    record Err(String error, String description) implements Result {}

    static Result ok(ClientRegistrationResponse r) {
      return new Ok(r);
    }

    static Result error(String e, String d) {
      return new Err(e, d);
    }
  }
}
