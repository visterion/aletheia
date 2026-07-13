package de.visterion.aletheia.oauth;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the OAuth endpoints. MCP clients (Claude.ai, ChatGPT) may invoke
 * discovery and registration from a browser context, which requires preflight OPTIONS to be
 * answered with appropriate Access-Control-* headers.
 *
 * <p>Allowlist is intentionally narrow — only the known MCP host origins. Add new origins here
 * when supporting additional clients.
 */
@Configuration
public class OAuthCorsConfig {

  // Claude uses both claude.ai and claude.com (and app subdomains); ChatGPT its own hosts.
  // Patterns (allowedOriginPatterns) so a subdomain variant doesn't break the connector.
  private static final List<String> ALLOWED_ORIGINS =
      List.of(
          "https://claude.ai",
          "https://*.claude.ai",
          "https://claude.com",
          "https://*.claude.com",
          "https://chatgpt.com",
          "https://chat.openai.com");

  @Bean
  public WebMvcConfigurer oauthCorsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry
            .addMapping("/.well-known/oauth-**")
            .allowedOriginPatterns(ALLOWED_ORIGINS.toArray(String[]::new))
            .allowedMethods("GET", "OPTIONS")
            .maxAge(3600);
        registry
            .addMapping("/oauth/**")
            .allowedOriginPatterns(ALLOWED_ORIGINS.toArray(String[]::new))
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Accept")
            .maxAge(3600);
        // The MCP endpoint itself: a browser-context MCP client (claude.ai) preflights /mcp and
        // must be able to read the 401 + WWW-Authenticate (RFC 9728) that bootstraps OAuth, plus
        // the Mcp-Session-Id on the Streamable HTTP transport. Auth still applies (the AuthFilter
        // guards non-OPTIONS /mcp); this only makes the endpoint CORS-reachable from the browser.
        registry
            .addMapping("/mcp/**")
            .allowedOriginPatterns(ALLOWED_ORIGINS.toArray(String[]::new))
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders(
                "Authorization", "Content-Type", "Accept", "Mcp-Session-Id", "Mcp-Protocol-Version")
            .exposedHeaders("WWW-Authenticate", "Mcp-Session-Id")
            .maxAge(3600);
        registry
            .addMapping("/mcp")
            .allowedOriginPatterns(ALLOWED_ORIGINS.toArray(String[]::new))
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders(
                "Authorization", "Content-Type", "Accept", "Mcp-Session-Id", "Mcp-Protocol-Version")
            .exposedHeaders("WWW-Authenticate", "Mcp-Session-Id")
            .maxAge(3600);
      }
    };
  }
}
