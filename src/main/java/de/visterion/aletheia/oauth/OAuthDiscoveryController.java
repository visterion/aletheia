package de.visterion.aletheia.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes OAuth 2.0 server metadata so MCP clients can self-discover the authorization, token,
 * and registration endpoints.
 *
 * <ul>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc8414">RFC 8414 — OAuth 2.0
 *       Authorization Server Metadata</a>
 *   <li>OAuth 2.0 Protected Resource Metadata (draft) — used by MCP clients (Claude.ai, ChatGPT)
 *       to map a resource server (the {@code /mcp} endpoint) back to its authorization server.
 * </ul>
 *
 * Both endpoints are public (no auth) — they advertise capability, not data.
 */
@RestController
public class OAuthDiscoveryController {

  private final OAuthProperties props;

  public OAuthDiscoveryController(OAuthProperties props) {
    this.props = props;
  }

  @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> authorizationServerMetadata() {
    if (!props.isEnabled()) return ResponseEntity.notFound().build();
    String issuer = props.getIssuer();
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("issuer", issuer);
    meta.put("authorization_endpoint", issuer + "/oauth/authorize");
    meta.put("token_endpoint", issuer + "/oauth/token");
    if (props.isDynamicClientRegistrationEnabled()) {
      meta.put("registration_endpoint", issuer + "/oauth/register");
    }
    meta.put("response_types_supported", List.of("code"));
    meta.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
    meta.put("token_endpoint_auth_methods_supported", List.of("none")); // public clients only
    meta.put("code_challenge_methods_supported", List.of("S256")); // PKCE mandatory
    meta.put("scopes_supported", List.of("read", "write"));
    meta.put("service_documentation", issuer + "/documentation/oauth.md");
    return ResponseEntity.ok(meta);
  }

  /**
   * Resource server metadata. MCP clients fetch this from the resource server URL (the {@code
   * /mcp} endpoint) to discover which authorization server protects it.
   */
  @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
    if (!props.isEnabled()) return ResponseEntity.notFound().build();
    String issuer = props.getIssuer();
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("resource", issuer);
    meta.put("authorization_servers", List.of(issuer));
    meta.put("scopes_supported", List.of("read", "write"));
    meta.put("bearer_methods_supported", List.of("header"));
    meta.put("resource_documentation", issuer + "/documentation/tools.md");
    return ResponseEntity.ok(meta);
  }
}
