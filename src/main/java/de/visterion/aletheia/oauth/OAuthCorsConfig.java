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

  private static final List<String> ALLOWED_ORIGINS =
      List.of("https://claude.ai", "https://chatgpt.com", "https://chat.openai.com");

  @Bean
  public WebMvcConfigurer oauthCorsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry
            .addMapping("/.well-known/oauth-**")
            .allowedOrigins(ALLOWED_ORIGINS.toArray(String[]::new))
            .allowedMethods("GET", "OPTIONS")
            .maxAge(3600);
        registry
            .addMapping("/oauth/**")
            .allowedOrigins(ALLOWED_ORIGINS.toArray(String[]::new))
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Accept")
            .maxAge(3600);
      }
    };
  }
}
