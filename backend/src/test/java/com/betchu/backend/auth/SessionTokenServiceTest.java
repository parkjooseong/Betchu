package com.betchu.backend.auth;

import static org.assertj.core.api.Assertions.*;

import com.betchu.backend.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionTokenServiceTest {
  private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
  private final Instant now = Instant.parse("2026-09-07T00:00:00Z");

  private AuthSettings settings(String key) {
    return new AuthSettings(
        "test-client",
        "test-secret",
        "http://localhost:8080/callback",
        List.of("betchu://auth/callback"),
        key);
  }

  @Test
  void signsVerifiesAndExpiresAccessTokens() {
    var issuer = new SessionTokenService(settings(TEST_KEY), Clock.fixed(now, ZoneOffset.UTC));
    UUID user = UUID.randomUUID(), session = UUID.randomUUID();
    String token = issuer.issue(user, session);
    assertThat(issuer.verify(token)).isEqualTo(new AuthenticatedUser(user, session));
    var later =
        new SessionTokenService(
            settings(TEST_KEY), Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC));
    assertThatThrownBy(() -> later.verify(token)).isInstanceOf(ApiException.class);
    String[] parts = token.split("\\.");
    assertThatThrownBy(() -> issuer.verify(parts[0] + "." + parts[1] + ".invalid-signature"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void refusesUnconfiguredAndWeakSigningSecrets() {
    var unavailable = new SessionTokenService(settings(""), Clock.fixed(now, ZoneOffset.UTC));
    assertThat(unavailable.configured()).isFalse();
    assertThatThrownBy(() -> unavailable.issue(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> new SessionTokenService(settings("d2Vhaw=="), Clock.fixed(now, ZoneOffset.UTC)))
        .isInstanceOf(IllegalStateException.class);
  }
}
