package de.visterion.aletheia.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit truth table for {@link Pkce}. No Spring context — {@link Pkce} has no dependencies
 * beyond the JDK.
 */
class PkceTest {

  /** RFC 7636 Appendix B test vector. */
  private static final String RFC7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

  private static final String RFC7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

  @Test
  void computeS256ChallengeMatchesRfc7636TestVector() {
    assertThat(Pkce.computeS256Challenge(RFC7636_VERIFIER)).isEqualTo(RFC7636_CHALLENGE);
  }

  @Test
  void verifyReturnsTrueForCorrectVerifierAndItsS256Challenge() {
    String verifier = "correct-verifier-1234567890";
    String challenge = Pkce.computeS256Challenge(verifier);

    assertThat(Pkce.verify(verifier, challenge, "S256")).isTrue();
  }

  @Test
  void verifyReturnsFalseForWrongVerifier() {
    String challenge = Pkce.computeS256Challenge("correct-verifier-1234567890");

    assertThat(Pkce.verify("wrong-verifier-0987654321", challenge, "S256")).isFalse();
  }

  @Test
  void verifyRejectsPlainMethodEvenWithMatchingChallenge() {
    String verifier = "correct-verifier-1234567890";
    // "plain" method means challenge == verifier verbatim; still must be rejected.
    assertThat(Pkce.verify(verifier, verifier, "plain")).isFalse();
  }

  @Test
  void verifyRejectsNullMethod() {
    String verifier = "correct-verifier-1234567890";
    String challenge = Pkce.computeS256Challenge(verifier);

    assertThat(Pkce.verify(verifier, challenge, null)).isFalse();
  }

  @Test
  void verifyReturnsFalseForNullVerifier() {
    String challenge = Pkce.computeS256Challenge("correct-verifier-1234567890");

    assertThat(Pkce.verify(null, challenge, "S256")).isFalse();
  }

  @Test
  void verifyReturnsFalseForEmptyVerifier() {
    String challenge = Pkce.computeS256Challenge("correct-verifier-1234567890");

    assertThat(Pkce.verify("", challenge, "S256")).isFalse();
  }

  @Test
  void verifyReturnsFalseForNullChallenge() {
    assertThat(Pkce.verify("correct-verifier-1234567890", null, "S256")).isFalse();
  }

  @Test
  void verifyReturnsFalseForEmptyChallenge() {
    assertThat(Pkce.verify("correct-verifier-1234567890", "", "S256")).isFalse();
  }

  @Test
  void computeS256ChallengeRejectsNullVerifier() {
    assertThat(catchException(() -> Pkce.computeS256Challenge(null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void computeS256ChallengeRejectsEmptyVerifier() {
    assertThat(catchException(() -> Pkce.computeS256Challenge(""))).isInstanceOf(IllegalArgumentException.class);
  }

  private static Exception catchException(Runnable runnable) {
    try {
      runnable.run();
      return null;
    } catch (Exception e) {
      return e;
    }
  }
}
