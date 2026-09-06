package com.betchu.backend.auth;

import java.time.Clock;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class GoogleTokenValidator implements OAuth2TokenValidator<Jwt> {
  private final String clientId;
  private final Clock clock;

  GoogleTokenValidator(String clientId, Clock clock) {
    this.clientId = clientId;
    this.clock = clock;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String authorizedParty = token.getClaimAsString("azp");
    String issuer = token.getClaimAsString("iss");
    List<String> audience = token.getAudience();
    boolean valid =
        token.getExpiresAt() != null
            && token.getIssuedAt() != null
            && !token.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
            && ("https://accounts.google.com".equals(issuer)
                || "accounts.google.com".equals(issuer))
            && audience != null
            && audience.contains(clientId)
            && (authorizedParty == null ? audience.size() == 1 : clientId.equals(authorizedParty))
            && token.getSubject() != null
            && !token.getSubject().isBlank()
            && token.getSubject().length() <= 255;
    return valid
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
  }

  static boolean validNonce(Jwt token, String nonceHash) {
    return AuthSecrets.matches(token.getClaimAsString("nonce"), nonceHash);
  }
}
