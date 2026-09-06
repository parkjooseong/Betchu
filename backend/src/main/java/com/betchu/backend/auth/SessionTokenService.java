package com.betchu.backend.auth;

import com.betchu.backend.common.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class SessionTokenService {
  public static final long ACCESS_SECONDS = 300;
  private static final String ISSUER = "betchu";
  private final Clock clock;
  private final NimbusJwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  public SessionTokenService(AuthSettings settings, Clock clock) {
    this.clock = clock;
    if (settings.signingSecret().isBlank()) {
      encoder = null;
      decoder = null;
      return;
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(settings.signingSecret());
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "JWT_SIGNING_SECRET must be Base64 with at least 32 random bytes");
    }
    if (key.length < 32)
      throw new IllegalStateException("JWT_SIGNING_SECRET must contain at least 32 random bytes");
    var secret = new SecretKeySpec(key, "HmacSHA256");
    encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret));
    decoder = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
    var timestamps = new JwtTimestampValidator(Duration.ZERO);
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamps,
            new JwtIssuerValidator(ISSUER),
            jwt ->
                jwt.getExpiresAt() != null
                        && jwt.getAudience().contains("betchu-api")
                        && "access".equals(jwt.getClaimAsString("token_use"))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
  }

  public boolean configured() {
    return encoder != null;
  }

  public String issue(UUID userId, UUID sessionId) {
    if (!configured()) throw unavailable();
    Instant now = clock.instant();
    var claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .audience(List.of("betchu-api"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(ACCESS_SECONDS))
            .id(UUID.randomUUID().toString())
            .claim("sid", sessionId.toString())
            .claim("token_use", "access")
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  public AuthenticatedUser verify(String token) {
    if (!configured()) throw unauthorized();
    try {
      Jwt jwt = decoder.decode(token);
      return new AuthenticatedUser(
          UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")));
    } catch (RuntimeException error) {
      throw unauthorized();
    }
  }

  static ApiException unauthorized() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "로그인이 만료되었어요. 다시 로그인해 주세요.");
  }

  static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "로그인 설정을 준비하고 있어요. 잠시 후 다시 시도해 주세요.");
  }
}
