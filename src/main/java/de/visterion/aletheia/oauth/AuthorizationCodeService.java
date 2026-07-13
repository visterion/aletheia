package de.visterion.aletheia.oauth;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues and consumes authorization codes for the OAuth code grant. Codes are stored as SHA-256
 * hashes; the plaintext value is returned to the caller once (then sent to the client via
 * redirect URI) and never persisted in plaintext.
 */
@Service
public class AuthorizationCodeService {

  private static final SecureRandom RNG = new SecureRandom();

  private final OAuthRepository repo;
  private final OAuthProperties props;

  public AuthorizationCodeService(OAuthRepository repo, OAuthProperties props) {
    this.repo = repo;
    this.props = props;
  }

  public String issue(
      String clientId,
      String redirectUri,
      String scope,
      String codeChallenge,
      String codeChallengeMethod,
      UUID userTokenId) {
    String code = generateCode();
    OffsetDateTime expiresAt = OffsetDateTime.now().plus(props.getAuthorizationCodeTtl());
    repo.insertAuthorizationCode(
        TokenHasher.sha256(code),
        clientId,
        redirectUri,
        scope,
        codeChallenge,
        codeChallengeMethod,
        userTokenId,
        expiresAt);
    return code;
  }

  /** Atomically consumes a code; returns the row exactly once, empty thereafter. */
  public Optional<OAuthRepository.AuthorizationCode> consume(String code) {
    if (code == null || code.isEmpty()) return Optional.empty();
    return repo.consumeAuthorizationCode(TokenHasher.sha256(code));
  }

  private static String generateCode() {
    byte[] bytes = new byte[32];
    RNG.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
