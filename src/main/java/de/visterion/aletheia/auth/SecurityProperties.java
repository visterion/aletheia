package de.visterion.aletheia.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Security posture knobs that depend on the network topology Aletheia is deployed behind. */
@Component
@ConfigurationProperties(prefix = "aletheia.security")
public class SecurityProperties {

  /**
   * Whether Aletheia is reachable only through a trusted reverse proxy (e.g. the Cloudflare
   * Tunnel used in production) that stamps every request with {@code CF-Connecting-IP} set to
   * the real client address — a header the tunnel itself injects/overwrites, so it cannot be
   * spoofed by a client going through it.
   *
   * <p>When {@code true} (the default — production is Cloudflare-Tunnel-only), rate limiting
   * keys on that header when present. When {@code false} (e.g. direct LAN access with no
   * trusted proxy in front), rate limiting falls back to the raw TCP peer address.
   */
  private boolean trustedProxy = true;

  public boolean isTrustedProxy() {
    return trustedProxy;
  }

  public void setTrustedProxy(boolean trustedProxy) {
    this.trustedProxy = trustedProxy;
  }
}
