package com.betchu.backend.config;

import static org.assertj.core.api.Assertions.*;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionGuardTest {
  private MockEnvironment valid() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    return new MockEnvironment()
        .withProperty(
            "spring.datasource.url", "jdbc:postgresql://db.internal/betchu?sslmode=verify-full")
        .withProperty("spring.datasource.username", "betchu")
        .withProperty("spring.datasource.password", "dedicated-long-test-password")
        .withProperty("betchu.auth.signing-secret", secret())
        .withProperty("betchu.couples.invite-hmac-secret", secret())
        .withProperty("betchu.auth.apple.token-encryption-secret", secret())
        .withProperty("betchu.auth.google.client-id", "google-id")
        .withProperty("betchu.auth.google.client-secret", "google-secret")
        .withProperty("betchu.auth.apple.client-id", "apple-id")
        .withProperty("betchu.auth.apple.team-id", "team")
        .withProperty("betchu.auth.apple.key-id", "key")
        .withProperty(
            "betchu.auth.apple.private-key-base64",
            Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded()))
        .withProperty(
            "betchu.auth.google.callback-uri",
            "https://api.betchu.app/api/v1/auth/oauth/google/callback")
        .withProperty(
            "betchu.auth.apple.callback-uri",
            "https://api.betchu.app/api/v1/auth/oauth/apple/callback")
        .withProperty(
            "betchu.auth.redirect-uris",
            "betchu://auth/callback,https://app.betchu.app/auth/callback")
        .withProperty("betchu.cors.allowed-origins", "https://app.betchu.app");
  }

  private String secret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @Test
  void requiresSecureIndependentKeysCallbacksAndDatabaseWithoutLeakingValues() throws Exception {
    var env = valid();
    assertThatCode(() -> ProductionGuard.validate(env)).doesNotThrowAnyException();
    env.withProperty("spring.datasource.password", "short-sensitive")
        .withProperty("betchu.auth.redirect-uris", "http://localhost:8081/auth/callback");
    assertThatThrownBy(() -> ProductionGuard.validate(env))
        .hasMessageContaining("DB_PASSWORD", "OAUTH_REDIRECT_URIS")
        .hasMessageNotContaining("short-sensitive");
  }

  @Test
  void rejectsSharedEncryptionKeyAndMissingPushCredential() throws Exception {
    var env = valid();
    env.withProperty(
            "betchu.couples.invite-hmac-secret", env.getProperty("betchu.auth.signing-secret"))
        .withProperty("betchu.notifications.enabled", "true");
    assertThatThrownBy(() -> ProductionGuard.validate(env))
        .hasMessageContaining("must be different", "expo-access-token");
  }

  @Test
  void rejectsUnsafeOrigins() {
    for (String url :
        new String[] {
          "http://app.betchu.app",
          "https://localhost",
          "https://127.0.0.1",
          "https://example.com",
          "https://app.betchu.app/path",
          "https://user:password@app.betchu.app",
          "https://app.betchu.app?redirect=evil"
        }) assertThat(ProductionGuard.publicHttps(url, "")).isFalse();
  }
}
