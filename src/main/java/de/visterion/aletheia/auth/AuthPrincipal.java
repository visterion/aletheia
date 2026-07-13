package de.visterion.aletheia.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated principal for a request. {@code tokenId} references the underlying {@code
 * api_tokens.id} when known — used by OAuth flows that need to bind issued authorization
 * codes / access tokens to the originating identity. May be {@code null} for legacy/test
 * contexts where the token row id was not resolved.
 */
public record AuthPrincipal(String name, AuthRole role, UUID tokenId) {

  public AuthPrincipal {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
  }

  /** Backward-compat constructor for callers that have no tokenId (legacy & tests). */
  public AuthPrincipal(String name, AuthRole role) {
    this(name, role, null);
  }
}
