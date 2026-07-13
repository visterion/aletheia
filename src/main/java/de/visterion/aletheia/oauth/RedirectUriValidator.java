package de.visterion.aletheia.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Validates redirect_uri values per RFC 6749 §3.1.2 and OAuth 2.1 best practice:
 *
 * <ul>
 *   <li>HTTPS always accepted
 *   <li>HTTP only on loopback (127.0.0.1, [::1], localhost) — for native/CLI clients
 *   <li>Custom schemes (e.g. {@code claude://}, {@code com.example.app://}) accepted for native
 *       apps
 *   <li>{@code javascript:}, {@code file:}, {@code data:} explicitly rejected
 *   <li>Fragments forbidden by spec
 * </ul>
 */
public final class RedirectUriValidator {
  private RedirectUriValidator() {}

  private static final Set<String> FORBIDDEN_SCHEMES =
      Set.of("javascript", "file", "data", "vbscript", "about");
  private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "[::1]", "localhost");

  public static boolean isAcceptable(String uri) {
    if (uri == null || uri.isBlank()) return false;
    URI parsed;
    try {
      parsed = new URI(uri);
    } catch (URISyntaxException e) {
      return false;
    }
    String scheme = parsed.getScheme();
    if (scheme == null) return false;
    if (parsed.getFragment() != null) return false;
    scheme = scheme.toLowerCase();
    if (FORBIDDEN_SCHEMES.contains(scheme)) return false;
    if ("https".equals(scheme)) return true;
    if ("http".equals(scheme)) {
      String host = parsed.getHost();
      if (host == null) return false;
      return LOOPBACK_HOSTS.contains(host.toLowerCase());
    }
    // Custom schemes (mobile apps, native MCP clients) — must be at least 3 chars to be a
    // recognizable URI scheme rather than a typo.
    return scheme.length() >= 3;
  }
}
