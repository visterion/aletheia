package de.visterion.aletheia.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Hashes OAuth bearer values (authorization codes, access tokens, refresh tokens) to their
 * SHA-256 base64url-encoded form for storage and lookup. Single source of truth for token
 * hashing across the OAuth subsystem and {@code AuthFilter}.
 */
public final class TokenHasher {
  private TokenHasher() {}

  public static String sha256(String input) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha.digest(input.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available", e);
    }
  }
}
