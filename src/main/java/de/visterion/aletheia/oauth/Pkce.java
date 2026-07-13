package de.visterion.aletheia.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * PKCE (RFC 7636) helpers. Only the {@code S256} method is supported — {@code plain} is rejected
 * even though the spec permits it, because every modern client supports S256 and accepting plain
 * weakens the protection.
 */
public final class Pkce {
  private Pkce() {}

  public static String computeS256Challenge(String verifier) {
    if (verifier == null || verifier.isEmpty()) {
      throw new IllegalArgumentException("verifier must not be empty");
    }
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available", e);
    }
  }

  public static boolean verify(String verifier, String challenge, String method) {
    if (verifier == null || verifier.isEmpty()) return false;
    if (challenge == null || challenge.isEmpty()) return false;
    if (!"S256".equals(method)) return false;
    return constantTimeEquals(challenge, computeS256Challenge(verifier));
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ b.charAt(i);
    }
    return diff == 0;
  }
}
