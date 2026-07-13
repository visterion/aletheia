package de.visterion.aletheia.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Public metadata about a token for list/info views. Never includes the plaintext or hash.
 *
 * <p>Status derivation: revoked takes precedence over expired, which takes precedence over
 * active.
 */
public record TokenSummary(
    String name,
    AuthRole role,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt,
    OffsetDateTime revokedAt,
    Status status) {
  public enum Status {
    ACTIVE,
    EXPIRED,
    REVOKED;

    public static Status derive(OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
      if (revokedAt != null) {
        return REVOKED;
      }
      if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
        return EXPIRED;
      }
      return ACTIVE;
    }
  }
}
