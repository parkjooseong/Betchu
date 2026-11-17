package com.betchu.backend.auth;

import static com.betchu.backend.auth.AuthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Transactional
@TestPropertySource(
    properties = {
      "betchu.auth.signing-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "betchu.auth.apple.client-id=fixture",
      "betchu.auth.apple.team-id=fixture",
      "betchu.auth.apple.key-id=fixture",
      "betchu.auth.apple.private-key-base64=fixture",
      "betchu.auth.apple.callback-uri=https://api.betchu.app/api/v1/auth/oauth/apple/callback",
      "betchu.auth.apple.token-encryption-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    })
class AppleAuthorizationIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired AuthService auth;
  @Autowired AccountService accounts;
  @Autowired AppleRevocationService revocations;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean AppleIdentityClient apple;
  private final String encrypted = "encrypted-fixture-" + UUID.randomUUID();

  @BeforeEach
  void provider() {
    when(apple.authorizationUrl(anyString(), anyString()))
        .thenAnswer(
            call -> "https://appleid.apple.com/auth/authorize?state=" + call.getArgument(0));
    when(apple.exchange(anyString(), anyString()))
        .thenReturn(
            new VerifiedIdentity("APPLE", UUID.randomUUID().toString(), "Fixture", encrypted));
  }

  @Test
  void providerBindingPreservesAttemptAndAgeRejectionQueuesRevocation() {
    var start = auth.start(new StartRequest("APPLE", "betchu://auth/callback"));
    String state = parameter(start.authorizationUrl(), "state");
    assertThatThrownBy(() -> auth.callback("GOOGLE", state, "fixture-code", null))
        .isInstanceOf(ApiException.class);
    verify(apple, never()).exchange(anyString(), anyString());
    String redirect = auth.callback("APPLE", state, "fixture-code", null);
    assertThat(redirect).doesNotContain(encrypted).doesNotContain(start.loginSecret());
    var tokens =
        auth.login(
            new LoginRequest(
                start.loginId(), start.loginSecret(), parameter(redirect, "handoffCode")));
    assertThat(
            jdbc.queryForObject(
                "SELECT provider FROM oauth_identities WHERE user_id=?",
                String.class,
                tokens.user().id()))
        .isEqualTo("APPLE");
    assertThatThrownBy(() -> auth.callback("APPLE", state, "fixture-code", null))
        .isInstanceOf(ApiException.class);
    accounts.age(tokens.user().id(), false);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=?", Integer.class, tokens.user().id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM apple_token_revocations WHERE token_hash=?",
                Integer.class,
                AuthSecrets.hash(encrypted)))
        .isEqualTo(1);
    assertThatThrownBy(() -> auth.authenticate(tokens.accessToken()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void authorizationExpiringDuringProviderExchangeCannotLeakAnUnrevokedGrant() {
    var start = auth.start(new StartRequest("APPLE", "betchu://auth/callback"));
    when(apple.exchange(anyString(), anyString()))
        .thenAnswer(
            call -> {
              jdbc.update(
                  "UPDATE oauth_login_attempts SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                  start.loginId());
              return new VerifiedIdentity(
                  "APPLE", UUID.randomUUID().toString(), "Fixture", encrypted);
            });
    assertThat(
            auth.callback(
                "APPLE", parameter(start.authorizationUrl(), "state"), "fixture-code", null))
        .contains("status=FAILED")
        .doesNotContain("handoffCode");
    assertThat(
            jdbc.queryForObject(
                "SELECT encrypted_token FROM apple_token_revocations WHERE token_hash=?",
                String.class,
                AuthSecrets.hash(encrypted)))
        .isEqualTo(encrypted);
  }

  @Test
  void revocationRetriesTransientErrorsAndErasesProviderTokenOnCompletion() {
    revocations.enqueue(encrypted);
    revocations.enqueue(encrypted);
    doThrow(new IllegalStateException("Provider unavailable"))
        .doNothing()
        .when(apple)
        .revoke(encrypted);
    revocations.process();
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM apple_token_revocations WHERE token_hash=?",
                Integer.class,
                AuthSecrets.hash(encrypted)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT completed_at IS NULL AND next_attempt_at>clock_timestamp() FROM apple_token_revocations WHERE token_hash=?",
                Boolean.class,
                AuthSecrets.hash(encrypted)))
        .isTrue();
    jdbc.update(
        "UPDATE apple_token_revocations SET next_attempt_at=clock_timestamp() WHERE token_hash=?",
        AuthSecrets.hash(encrypted));
    revocations.process();
    revocations.process();
    verify(apple, times(2)).revoke(encrypted);
    assertThat(
            jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL AND encrypted_token IS NULL FROM apple_token_revocations WHERE token_hash=?",
                Boolean.class,
                AuthSecrets.hash(encrypted)))
        .isTrue();
  }

  private String parameter(String url, String name) {
    return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
  }
}
