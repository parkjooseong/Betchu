package com.betchu.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.auth.AuthModels.*;
import com.betchu.backend.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;

@TestPropertySource(
    properties = {
      "betchu.auth.signing-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "betchu.auth.google.client-id=test-client",
      "betchu.auth.google.client-secret=test-secret"
    })
class AuthAccountIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired AuthService auth;
  @Autowired AccountService accounts;
  @Autowired PolicyService policies;
  @Autowired SessionTokenService tokens;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean GoogleIdentityClient google;
  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> policyIds = new ArrayList<>();
  private final List<UUID> attempts = new ArrayList<>();

  @AfterEach
  void removeOnlyThisTestsFixtures() {
    for (UUID id : attempts) jdbc.update("DELETE FROM oauth_login_attempts WHERE id=?", id);
    for (UUID id : users) {
      jdbc.update("DELETE FROM wallet_ledger WHERE user_id=?", id);
      jdbc.update("DELETE FROM wallets WHERE user_id=?", id);
      jdbc.update("DELETE FROM users WHERE id=?", id);
    }
    for (UUID id : policyIds) jdbc.update("DELETE FROM policy_versions WHERE id=?", id);
  }

  private UUID pending() {
    UUID id = UUID.randomUUID();
    users.add(id);
    jdbc.update("INSERT INTO users(id,nickname,status) VALUES (?,'테스트','PENDING_ELIGIBILITY')", id);
    return id;
  }

  private UUID policy(String type) {
    UUID id = UUID.randomUUID();
    policyIds.add(id);
    jdbc.update(
        "INSERT INTO policy_versions(id,policy_type,policy_version,locale,required,document_url,effective_at,status) VALUES (?,?,?,'ko-KR',?,'https://example.test/approved-policy',?,'ACTIVE')",
        id,
        type,
        id.toString(),
        !"EVIDENCE_OPTIONAL".equals(type),
        Timestamp.from(Instant.now().minusSeconds(60)));
    return id;
  }

  private void accept(UUID user, String type, UUID policy) {
    accounts.consent(user, type, new ConsentRequest(policy, "ko-KR"));
  }

  private UUID activate() {
    UUID id = pending();
    UUID terms = policy("TERMS"), privacy = policy("PRIVACY");
    accounts.age(id, true);
    accept(id, "TERMS", terms);
    accept(id, "PRIVACY", privacy);
    return id;
  }

  private long count(String sql, UUID id) {
    return jdbc.queryForObject(sql, Long.class, id);
  }

  @Test
  void missingApprovedPoliciesNeverActivatesOrGrantsCoins() {
    UUID user = pending();
    assertThat(policies.current("ko-KR").ready()).isFalse();
    UserMe me = accounts.age(user, true);
    assertThat(me.status()).isEqualTo("PENDING_ELIGIBILITY");
    assertThat(me.availableCoins()).isNull();
    assertThatThrownBy(() -> accounts.requireGeneralAccess(user)).isInstanceOf(ApiException.class);
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger WHERE user_id=?", user)).isZero();
  }

  @Test
  void finalRequiredConsentActivatesAndGrantsExactlyOnce() {
    UUID user = pending(),
        terms = policy("TERMS"),
        privacy = policy("PRIVACY"),
        evidence = policy("EVIDENCE_OPTIONAL");
    accept(user, "TERMS", terms);
    accept(user, "PRIVACY", privacy);
    assertThat(accounts.me(user).status()).isEqualTo("PENDING_ELIGIBILITY");
    var first = accounts.age(user, true);
    assertThat(first.status()).isEqualTo("ACTIVE");
    assertThat(first.availableCoins()).isEqualTo(1000);
    assertThat(first.lockedCoins()).isZero();
    assertThat(first.evidenceConsent()).isFalse();
    accounts.age(user, true);
    accept(user, "TERMS", terms);
    accept(user, "PRIVACY", privacy);
    accept(user, "EVIDENCE_OPTIONAL", evidence);
    assertThat(accounts.me(user).evidenceConsent()).isTrue();
    assertThat(accounts.me(user).availableCoins()).isEqualTo(1000);
    assertThat(
            count(
                "SELECT COUNT(*) FROM wallet_ledger WHERE user_id=? AND entry_type='SIGNUP_GRANT'",
                user))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM policy_consents WHERE user_id=?", user)).isEqualTo(3);
  }

  @Test
  void concurrentFinalConsentsCreateOneWalletAndOneGrant() throws Exception {
    UUID user = pending(), terms = policy("TERMS"), privacy = policy("PRIVACY");
    accounts.age(user, true);
    accept(user, "TERMS", terms);
    try (var pool = Executors.newFixedThreadPool(6)) {
      List<Callable<UserMe>> calls = new ArrayList<>();
      for (int index = 0; index < 6; index++)
        calls.add(() -> accounts.consent(user, "PRIVACY", new ConsentRequest(privacy, "ko-KR")));
      for (Future<UserMe> result : pool.invokeAll(calls))
        assertThat(result.get().availableCoins()).isEqualTo(1000);
    }
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger WHERE user_id=?", user)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM wallets WHERE user_id=?", user)).isEqualTo(1);
  }

  @Test
  void grantFailureRollsBackConsentWalletAndActivation() {
    UUID user = pending(), terms = policy("TERMS"), privacy = policy("PRIVACY");
    accounts.age(user, true);
    accept(user, "TERMS", terms);
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'SIGNUP_GRANT',1000,0)",
        UUID.randomUUID(),
        user);
    assertThatThrownBy(() -> accept(user, "PRIVACY", privacy))
        .isInstanceOf(DataIntegrityViolationException.class);
    UserMe me = accounts.me(user);
    assertThat(me.status()).isEqualTo("PENDING_ELIGIBILITY");
    assertThat(me.availableCoins()).isNull();
    assertThat(me.missingPolicyVersionIds()).containsExactly(privacy);
  }

  @Test
  void policyReplacementGatesAnAlreadyActiveAccountUntilReconsent() {
    UUID user = activate();
    accounts.requireGeneralAccess(user);
    UUID old = policyIds.get(1);
    jdbc.update("UPDATE policy_versions SET status='RETIRED' WHERE id=?", old);
    UUID current = policy("PRIVACY");
    assertThat(accounts.me(user).missingPolicyVersionIds()).containsExactly(current);
    assertThatThrownBy(() -> accounts.requireGeneralAccess(user)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> accept(user, "PRIVACY", old)).isInstanceOf(ApiException.class);
    accept(user, "PRIVACY", current);
    accounts.requireGeneralAccess(user);
    assertThat(accounts.me(user).availableCoins()).isEqualTo(1000);
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger WHERE user_id=?", user)).isEqualTo(1);
  }

  @Test
  void wrongTypeLocaleAndFuturePoliciesCannotBeAccepted() {
    UUID user = pending(), terms = policy("TERMS");
    assertThatThrownBy(() -> accept(user, "PRIVACY", terms)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> accounts.consent(user, "TERMS", new ConsentRequest(terms, "en-US")))
        .isInstanceOf(ApiException.class);
    jdbc.update(
        "UPDATE policy_versions SET effective_at=? WHERE id=?",
        Timestamp.from(Instant.now().plusSeconds(3600)),
        terms);
    assertThatThrownBy(() -> accept(user, "TERMS", terms)).isInstanceOf(ApiException.class);
    assertThat(count("SELECT COUNT(*) FROM policy_consents WHERE user_id=?", user)).isZero();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = false)
  void rejectedAgeDeletesPendingIdentityAndAllSessionsImmediately(Boolean eligible) {
    UUID user = pending();
    jdbc.update(
        "INSERT INTO oauth_identities(user_id,provider,provider_subject) VALUES (?,'GOOGLE',?)",
        user,
        UUID.randomUUID().toString());
    UUID session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO auth_sessions(id,user_id,refresh_token_hash,status,expires_at) VALUES (?,?,?,'ACTIVE',?)",
        session,
        user,
        AuthSecrets.hash(AuthSecrets.randomToken()),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    String token = tokens.issue(user, session);
    auth.authenticate(token);
    assertThat(accounts.age(user, eligible)).isNull();
    assertThat(count("SELECT COUNT(*) FROM users WHERE id=?", user)).isZero();
    assertThat(count("SELECT COUNT(*) FROM auth_sessions WHERE user_id=?", user)).isZero();
    assertThat(count("SELECT COUNT(*) FROM oauth_identities WHERE user_id=?", user)).isZero();
    assertThatThrownBy(() -> auth.authenticate(token)).isInstanceOf(ApiException.class);
  }

  @Test
  void repeatedAgeRejectionCannotDeleteAnActiveAccount() {
    UUID user = activate();
    assertThatThrownBy(() -> accounts.age(user, false)).isInstanceOf(ApiException.class);
    assertThat(accounts.me(user).status()).isEqualTo("ACTIVE");
    assertThat(accounts.me(user).availableCoins()).isEqualTo(1000);
  }

  private LoginStart begin() {
    when(google.authorizationUrl(anyString(), anyString(), anyString()))
        .thenAnswer(call -> "https://accounts.google.com/authorize?state=" + call.getArgument(0));
    LoginStart start = auth.start(new StartRequest("GOOGLE", "betchu://auth/callback"));
    attempts.add(start.loginId());
    return start;
  }

  private String state(LoginStart start) {
    return UriComponentsBuilder.fromUriString(start.authorizationUrl())
        .build()
        .getQueryParams()
        .getFirst("state");
  }

  private Tokens login() {
    var start = begin();
    when(google.exchange(eq("test-code"), anyString(), anyString()))
        .thenReturn(new VerifiedIdentity(UUID.randomUUID().toString(), "테스트"));
    String redirect = auth.callback(state(start), "test-code", null);
    Tokens result =
        auth.login(new LoginRequest(start.loginId(), start.loginSecret(), handoff(redirect)));
    users.add(result.user().id());
    return result;
  }

  private String handoff(String redirect) {
    return UriComponentsBuilder.fromUriString(redirect)
        .build()
        .getQueryParams()
        .getFirst("handoffCode");
  }

  @Test
  void googleAttemptUsesHashesOneTimeStateAndSecretWithoutRedirectingTokens() {
    LoginStart start = begin();
    String state = state(start);
    assertThat(
            jdbc.queryForObject(
                "SELECT login_secret_hash FROM oauth_login_attempts WHERE id=?",
                String.class,
                start.loginId()))
        .isEqualTo(AuthSecrets.hash(start.loginSecret()));
    assertThat(
            jdbc.queryForObject(
                "SELECT state_hash FROM oauth_login_attempts WHERE id=?",
                String.class,
                start.loginId()))
        .isEqualTo(AuthSecrets.hash(state));
    when(google.exchange(eq("test-code"), anyString(), anyString()))
        .thenReturn(new VerifiedIdentity(UUID.randomUUID().toString(), "테스트"));
    String redirect = auth.callback(state, "test-code", null);
    String handoff = handoff(redirect);
    assertThat(handoff).hasSize(43);
    assertThat(
            jdbc.queryForObject(
                "SELECT handoff_hash FROM oauth_login_attempts WHERE id=?",
                String.class,
                start.loginId()))
        .isEqualTo(AuthSecrets.hash(handoff));
    assertThat(redirect)
        .startsWith("betchu://auth/callback?")
        .contains("status=SUCCESS")
        .contains("loginId=" + start.loginId())
        .doesNotContain(start.loginSecret())
        .doesNotContain("accessToken")
        .doesNotContain("refreshToken");
    assertThatThrownBy(() -> auth.callback(state, "test-code", null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> auth.login(new LoginRequest(start.loginId(), "incorrect", handoff)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> auth.login(new LoginRequest(start.loginId(), start.loginSecret(), null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                auth.login(new LoginRequest(start.loginId(), start.loginSecret(), "wrong-handoff")))
        .isInstanceOf(ApiException.class);
    Tokens result = auth.login(new LoginRequest(start.loginId(), start.loginSecret(), handoff));
    users.add(result.user().id());
    assertThat(result.user().status()).isEqualTo("PENDING_ELIGIBILITY");
    assertThat(result.user().availableCoins()).isNull();
    auth.authenticate(result.accessToken());
    assertThatThrownBy(
            () -> auth.login(new LoginRequest(start.loginId(), start.loginSecret(), handoff)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void failureAndExpiredAttemptsCannotIssueSessions() {
    LoginStart start = begin();
    assertThat(auth.callback(state(start), null, "access_denied"))
        .contains("status=FAILED")
        .doesNotContain("handoffCode");
    assertThatThrownBy(
            () -> auth.login(new LoginRequest(start.loginId(), start.loginSecret(), "unused")))
        .isInstanceOf(ApiException.class);
    verify(google, never()).exchange(anyString(), anyString(), anyString());
    LoginStart expired = begin();
    jdbc.update(
        "UPDATE oauth_login_attempts SET expires_at=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        expired.loginId());
    assertThatThrownBy(() -> auth.callback(state(expired), "test-code", null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> auth.start(new StartRequest("GOOGLE", "https://untrusted.example/callback")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void refreshRotatesAndLogoutRevokesAccessAndRefreshImmediately() {
    Tokens first = login(), next = auth.refresh(first.refreshToken());
    assertThat(next.refreshToken()).isNotEqualTo(first.refreshToken());
    assertThatThrownBy(() -> auth.refresh(first.refreshToken())).isInstanceOf(ApiException.class);
    AuthenticatedUser principal = auth.authenticate(next.accessToken());
    auth.logout(principal);
    assertThatThrownBy(() -> auth.authenticate(first.accessToken()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> auth.authenticate(next.accessToken()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> auth.refresh(next.refreshToken())).isInstanceOf(ApiException.class);
  }

  @Test
  void expiredHandoffCannotBeExchangedEvenWhenTheLoginAttemptIsStillValid() {
    LoginStart start = begin();
    when(google.exchange(eq("test-code"), anyString(), anyString()))
        .thenReturn(new VerifiedIdentity(UUID.randomUUID().toString(), "테스트"));
    String code = handoff(auth.callback(state(start), "test-code", null));
    jdbc.update(
        "UPDATE oauth_login_attempts SET handoff_expires_at=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        start.loginId());
    assertThatThrownBy(
            () -> auth.login(new LoginRequest(start.loginId(), start.loginSecret(), code)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void concurrentHandoffExchangeCreatesOnlyOneSession() throws Exception {
    LoginStart start = begin();
    when(google.exchange(eq("test-code"), anyString(), anyString()))
        .thenReturn(new VerifiedIdentity(UUID.randomUUID().toString(), "테스트"));
    String code = handoff(auth.callback(state(start), "test-code", null));
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Tokens> exchange =
          () -> {
            try {
              return auth.login(new LoginRequest(start.loginId(), start.loginSecret(), code));
            } catch (ApiException rejected) {
              return null;
            }
          };
      List<Future<Tokens>> results = pool.invokeAll(List.of(exchange, exchange));
      int successes = 0;
      for (Future<Tokens> result : results) {
        Tokens success = result.get();
        if (success != null) {
          users.add(success.user().id());
          successes++;
        }
      }
      assertThat(successes).isEqualTo(1);
      assertThat(count("SELECT COUNT(*) FROM auth_sessions WHERE user_id=?", users.getLast()))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentRefreshOnlyAcceptsOneUse() throws Exception {
    Tokens first = login();
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> refresh =
          () -> {
            try {
              auth.refresh(first.refreshToken());
              return true;
            } catch (ApiException invalid) {
              return false;
            }
          };
      List<Future<Boolean>> results = pool.invokeAll(List.of(refresh, refresh));
      int successes = 0;
      for (Future<Boolean> result : results) if (result.get()) successes++;
      assertThat(successes).isEqualTo(1);
    }
  }

  @Test
  void expiryCleanupRemovesTemporaryProfilesAndSensitiveAttempts() {
    UUID user = pending();
    jdbc.update(
        "UPDATE users SET created_at=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(24L * 3600)),
        user);
    LoginStart attempt = begin();
    jdbc.update(
        "UPDATE oauth_login_attempts SET expires_at=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        attempt.loginId());
    auth.cleanup();
    assertThat(count("SELECT COUNT(*) FROM users WHERE id=?", user)).isZero();
    assertThat(count("SELECT COUNT(*) FROM oauth_login_attempts WHERE id=?", attempt.loginId()))
        .isZero();
  }
}
