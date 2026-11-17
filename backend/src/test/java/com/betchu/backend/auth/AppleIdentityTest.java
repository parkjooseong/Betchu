package com.betchu.backend.auth;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AppleIdentityTest {
  private final Instant now = Instant.parse("2026-10-15T00:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private AppleSettings settings(String key) {
    return new AppleSettings(
        "app.betchu.web",
        "TEAMID",
        "KEYID",
        key,
        "https://api.example.com/api/v1/auth/oauth/apple/callback",
        Base64.getEncoder().encodeToString(new byte[32]));
  }

  @Test
  void appleTokenMustMatchIssuerAudienceAndTime() {
    var validator = new AppleTokenValidator("app.betchu.web", clock);
    var token =
        Jwt.withTokenValue("verified")
            .header("alg", "RS256")
            .issuer("https://appleid.apple.com")
            .subject("apple-user")
            .audience(List.of("app.betchu.web"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600));
    assertThat(validator.validate(token.build()).hasErrors()).isFalse();
    assertThat(validator.validate(token.audience(List.of("other")).build()).hasErrors()).isTrue();
    assertThat(
            validator
                .validate(
                    token
                        .audience(List.of("app.betchu.web"))
                        .issuer("https://attacker.example")
                        .build())
                .hasErrors())
        .isTrue();
    assertThat(
            validator
                .validate(
                    token
                        .issuer("https://appleid.apple.com")
                        .issuedAt(now.plusSeconds(100))
                        .build())
                .hasErrors())
        .isTrue();
  }

  @Test
  void providerTokenIsAuthenticatedEncryptedAndTamperResistant() {
    var cipher = new ProviderTokenCipher(settings(""));
    String plaintext = "private-provider-refresh-token";
    String first = cipher.encrypt(plaintext), second = cipher.encrypt(plaintext);
    assertThat(first).isNotEqualTo(second).doesNotContain(plaintext);
    assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
    byte[] damaged = Base64.getDecoder().decode(first);
    damaged[damaged.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(damaged)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void generatedClientSecretIsSignedAndExpiresInFiveMinutes() throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    var pair = generator.generateKeyPair();
    var settings = settings(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    var client = new AppleIdentityClient(settings, new ProviderTokenCipher(settings), clock);
    var jwt = SignedJWT.parse(client.clientSecret());
    assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic()))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isEqualTo(now.plusSeconds(300));
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
    assertThat(client.authorizationUrl("state", "nonce"))
        .contains("response_mode=form_post", "nonce=nonce", "state=state");
  }
}
