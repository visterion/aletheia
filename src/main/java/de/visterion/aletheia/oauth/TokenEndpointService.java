package de.visterion.aletheia.oauth;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Implements the OAuth 2.0 token endpoint logic for both grant types supported:
 *
 * <ul>
 *   <li>{@code authorization_code} — exchanges a code (with PKCE verifier) for an access+refresh
 *       token pair. The code is consumed atomically; replay attempts fail.
 *   <li>{@code refresh_token} — rotates the refresh token: the old one is revoked, a new
 *       access+refresh pair is issued. If a refresh token is reused after rotation, the entire
 *       chain is treated as compromised and everything linked through {@code parent_id} is
 *       revoked (RFC 6819 §5.2.2.3).
 * </ul>
 */
@Service
public class TokenEndpointService {

  private static final SecureRandom RNG = new SecureRandom();

  private final OAuthRepository repo;
  private final AuthorizationCodeService codes;
  private final OAuthProperties props;

  public TokenEndpointService(OAuthRepository repo, AuthorizationCodeService codes, OAuthProperties props) {
    this.repo = repo;
    this.codes = codes;
    this.props = props;
  }

  public Result exchangeCode(String code, String clientId, String redirectUri, String verifier) {
    Optional<OAuthRepository.AuthorizationCode> consumed = codes.consume(code);
    if (consumed.isEmpty()) {
      return Result.error("invalid_grant", "code unknown, expired, or already used");
    }
    OAuthRepository.AuthorizationCode auth = consumed.get();
    if (!auth.clientId().equals(clientId)) {
      return Result.error("invalid_grant", "code/client mismatch");
    }
    if (!auth.redirectUri().equals(redirectUri)) {
      return Result.error("invalid_grant", "redirect_uri mismatch");
    }
    if (!Pkce.verify(verifier, auth.codeChallenge(), auth.codeChallengeMethod())) {
      return Result.error("invalid_grant", "PKCE verification failed");
    }
    return Result.ok(issueTokenPair(clientId, auth.userTokenId(), auth.scope(), null));
  }

  public Result refresh(String refreshToken, String clientId) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return Result.error("invalid_request", "refresh_token required");
    }
    String tokenHash = TokenHasher.sha256(refreshToken);
    Optional<OAuthRepository.TokenLookup> active = repo.lookupActiveToken(tokenHash);
    if (active.isEmpty()) {
      // Could be replay of a rotated/revoked token — chain-revoke if we can find it.
      Optional<OAuthRepository.TokenLookup> any = repo.lookupAnyToken(tokenHash);
      if (any.isPresent() && "refresh".equals(any.get().kind())) {
        UUID root = repo.findChainRoot(any.get().id());
        repo.revokeChain(root);
      }
      return Result.error("invalid_grant", "refresh_token unknown, expired, or reused");
    }
    OAuthRepository.TokenLookup t = active.get();
    if (!"refresh".equals(t.kind())) {
      return Result.error("invalid_grant", "not a refresh token");
    }
    if (!t.clientId().equals(clientId)) {
      return Result.error("invalid_grant", "client_id mismatch");
    }
    // Rotate: revoke this refresh, issue a new pair linked via parent_id.
    repo.revokeToken(t.id());
    return Result.ok(issueTokenPair(clientId, t.userTokenId(), t.scope(), t.id()));
  }

  private TokenResponse issueTokenPair(String clientId, UUID userTokenId, String scope, UUID parentId) {
    String access = generateOpaqueToken();
    OffsetDateTime accessExpires = OffsetDateTime.now().plus(props.getAccessTokenTtl());
    repo.insertToken("access", TokenHasher.sha256(access), clientId, userTokenId, scope, parentId, accessExpires);

    String refresh = generateOpaqueToken();
    OffsetDateTime refreshExpires = OffsetDateTime.now().plus(props.getRefreshTokenTtl());
    repo.insertToken(
        "refresh", TokenHasher.sha256(refresh), clientId, userTokenId, scope, parentId, refreshExpires);

    return new TokenResponse(access, "Bearer", props.getAccessTokenTtl().toSeconds(), refresh, scope);
  }

  private static String generateOpaqueToken() {
    byte[] bytes = new byte[32];
    RNG.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public sealed interface Result permits Result.Ok, Result.Err {
    record Ok(TokenResponse response) implements Result {}

    record Err(String error, String description) implements Result {}

    static Result ok(TokenResponse r) {
      return new Ok(r);
    }

    static Result error(String e, String d) {
      return new Err(e, d);
    }
  }
}
