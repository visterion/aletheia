package de.visterion.aletheia.oauth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the OAuth 2.0 authorization server that exposes Aletheia as an MCP Custom
 * Connector to clients like Claude.ai.
 */
@Component
@ConfigurationProperties(prefix = "aletheia.oauth")
public class OAuthProperties {

  /** Whether OAuth endpoints are enabled. Disabled by default until a public HTTPS issuer is configured. */
  private boolean enabled = false;

  /**
   * Public HTTPS issuer URL (e.g. {@code https://aletheia.ufelmann.com}). Must match the URL
   * clients reach the discovery endpoints at — used as the {@code iss} value in token claims
   * and discovery metadata.
   */
  private String issuer = "";

  /** Lifetime of issued access tokens. Default: 1 hour. */
  private Duration accessTokenTtl = Duration.ofHours(1);

  /** Lifetime of issued refresh tokens. Default: 30 days. */
  private Duration refreshTokenTtl = Duration.ofDays(30);

  /** Lifetime of authorization codes between issue and exchange. Default: 10 minutes. */
  private Duration authorizationCodeTtl = Duration.ofMinutes(10);

  /** Whether to allow Dynamic Client Registration (RFC 7591). Required by the Claude.ai Custom Connector flow. */
  private boolean dynamicClientRegistrationEnabled = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public Duration getAccessTokenTtl() {
    return accessTokenTtl;
  }

  public void setAccessTokenTtl(Duration accessTokenTtl) {
    this.accessTokenTtl = accessTokenTtl;
  }

  public Duration getRefreshTokenTtl() {
    return refreshTokenTtl;
  }

  public void setRefreshTokenTtl(Duration refreshTokenTtl) {
    this.refreshTokenTtl = refreshTokenTtl;
  }

  public Duration getAuthorizationCodeTtl() {
    return authorizationCodeTtl;
  }

  public void setAuthorizationCodeTtl(Duration authorizationCodeTtl) {
    this.authorizationCodeTtl = authorizationCodeTtl;
  }

  public boolean isDynamicClientRegistrationEnabled() {
    return dynamicClientRegistrationEnabled;
  }

  public void setDynamicClientRegistrationEnabled(boolean dynamicClientRegistrationEnabled) {
    this.dynamicClientRegistrationEnabled = dynamicClientRegistrationEnabled;
  }
}
