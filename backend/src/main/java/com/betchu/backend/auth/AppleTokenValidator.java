package com.betchu.backend.auth;

import java.time.Clock;
import java.util.List;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;

final class AppleTokenValidator implements OAuth2TokenValidator<Jwt> {
  private final String clientId;
  private final Clock clock;

  AppleTokenValidator(String clientId, Clock clock) {
    this.clientId = clientId;
    this.clock = clock;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    boolean valid =
        "https://appleid.apple.com".equals(token.getClaimAsString("iss"))
            && List.of(clientId).equals(token.getAudience())
            && token.getExpiresAt() != null
            && token.getIssuedAt() != null
            && !token.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
            && token.getSubject() != null
            && !token.getSubject().isBlank()
            && token.getSubject().length() <= 255;
    return valid
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
  }
}
