package com.betchu.backend.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class GoogleTokenValidatorTest {
  private final Instant now = Instant.parse("2026-09-07T00:00:00Z");
  private final GoogleTokenValidator validator =
      new GoogleTokenValidator("expected-client", Clock.fixed(now, ZoneOffset.UTC));

  private Jwt.Builder valid() {
    return Jwt.withTokenValue("verified-test-token")
        .header("alg", "RS256")
        .issuer("https://accounts.google.com")
        .subject("google-user-id")
        .audience(List.of("expected-client"))
        .issuedAt(now)
        .expiresAt(now.plusSeconds(3600))
        .claim("nonce", "expected-nonce");
  }

  @Test
  void acceptsOnlyGoogleIssuerAndIntendedAudience() {
    assertThat(validator.validate(valid().build()).hasErrors()).isFalse();
    assertThat(validator.validate(valid().issuer("accounts.google.com").build()).hasErrors())
        .isFalse();
    assertThat(validator.validate(valid().issuer("https://untrusted.example").build()).hasErrors())
        .isTrue();
    assertThat(validator.validate(valid().audience(List.of("other-client")).build()).hasErrors())
        .isTrue();
    assertThat(validator.validate(valid().claim("azp", "other-client").build()).hasErrors())
        .isTrue();
    assertThat(
            validator
                .validate(valid().audience(List.of("expected-client", "other-client")).build())
                .hasErrors())
        .isTrue();
  }

  @Test
  void requiresExpirationSubjectAndPlausibleIssueTime() {
    assertThat(
            validator.validate(valid().claims(claims -> claims.remove("exp")).build()).hasErrors())
        .isTrue();
    assertThat(
            validator.validate(valid().claims(claims -> claims.remove("sub")).build()).hasErrors())
        .isTrue();
    assertThat(
            validator.validate(valid().claims(claims -> claims.remove("iss")).build()).hasErrors())
        .isTrue();
    assertThat(
            validator.validate(valid().claims(claims -> claims.remove("aud")).build()).hasErrors())
        .isTrue();
    assertThat(validator.validate(valid().issuedAt(now.plusSeconds(60)).build()).hasErrors())
        .isTrue();
  }

  @Test
  void rejectsMissingOrReplayedNonce() {
    String hash = AuthSecrets.hash("expected-nonce");
    assertThat(GoogleTokenValidator.validNonce(valid().build(), hash)).isTrue();
    assertThat(
            GoogleTokenValidator.validNonce(valid().claim("nonce", "other-attempt").build(), hash))
        .isFalse();
    assertThat(
            GoogleTokenValidator.validNonce(
                valid().claims(claims -> claims.remove("nonce")).build(), hash))
        .isFalse();
  }
}
