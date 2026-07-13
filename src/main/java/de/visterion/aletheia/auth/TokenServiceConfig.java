package de.visterion.aletheia.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class TokenServiceConfig {

  /**
   * Wraps DbTokenService with a Caffeine cache (60s TTL, max 1000 entries). Enabled by
   * default; set aletheia.token-cache.enabled=false to disable (useful in tests that provide
   * their own TokenService mock).
   */
  @Bean
  @Primary
  @ConditionalOnProperty(
      name = "aletheia.token-cache.enabled",
      havingValue = "true",
      matchIfMissing = true)
  CachedTokenService cachedTokenService(DbTokenService delegate) {
    return new CachedTokenService(delegate);
  }
}
