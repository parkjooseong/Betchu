package com.betchu.backend.auth;

import com.betchu.backend.auth.AuthModels.*;
import com.betchu.backend.common.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AuthService {
  record Attempt(
      UUID id,
      String secretHash,
      String redirectUri,
      String status,
      String nonceHash,
      String verifier,
      String subject,
      String nickname,
      Instant expiresAt,
      String handoffHash,
      Instant handoffExpiresAt) {}

  record Session(UUID id, UUID userId, String refreshHash, String status, Instant expiresAt) {}

  private final JdbcTemplate jdbc;
  private final AccountService accounts;
  private final PolicyService policies;
  private final AuthSettings settings;
  private final SessionTokenService tokens;
  private final GoogleIdentityClient google;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public AuthService(
      JdbcTemplate jdbc,
      AccountService accounts,
      PolicyService policies,
      AuthSettings settings,
      SessionTokenService tokens,
      GoogleIdentityClient google,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.policies = policies;
    this.settings = settings;
    this.tokens = tokens;
    this.google = google;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
  }

  public Providers providers() {
    boolean enabled = settings.googleConfigured() && tokens.configured();
    return new Providers(
        List.of(new Provider("GOOGLE", "Google", enabled)),
        enabled && policies.current("ko-KR").ready());
  }

  @Transactional
  public LoginStart start(StartRequest request) {
    if (!settings.googleConfigured() || !tokens.configured())
      throw SessionTokenService.unavailable();
    if (!"GOOGLE".equals(request.provider())
        || !settings.redirectUris().contains(request.redirectUri()))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_LOGIN_REQUEST", "로그인 종류와 돌아갈 주소를 확인해 주세요.");
    UUID id = UUID.randomUUID();
    String secret = AuthSecrets.randomToken();
    String state = AuthSecrets.randomToken();
    String nonce = AuthSecrets.randomToken();
    String verifier = AuthSecrets.randomToken();
    Instant expires = clock.instant().plusSeconds(600);
    jdbc.update(
        "INSERT INTO oauth_login_attempts(id,login_secret_hash,state_hash,nonce_hash,pkce_verifier,redirect_uri,status,expires_at,created_at) VALUES (?,?,?,?,?,?,'PENDING',?,?)",
        id,
        AuthSecrets.hash(secret),
        AuthSecrets.hash(state),
        AuthSecrets.hash(nonce),
        verifier,
        request.redirectUri(),
        Timestamp.from(expires),
        now());
    return new LoginStart(id, secret, google.authorizationUrl(state, nonce, verifier), expires);
  }

  public String callback(String state, String code, String error) {
    if (state == null || state.isBlank() || state.length() > 128) throw invalidCallback();
    Attempt attempt =
        transaction.execute(
            status -> {
              var found =
                  jdbc.query(
                      "SELECT * FROM oauth_login_attempts WHERE state_hash=? FOR UPDATE",
                      this::attempt,
                      AuthSecrets.hash(state));
              if (found.isEmpty()
                  || !"PENDING".equals(found.getFirst().status())
                  || !found.getFirst().expiresAt().isAfter(clock.instant()))
                throw invalidCallback();
              Attempt item = found.getFirst();
              jdbc.update(
                  "UPDATE oauth_login_attempts SET status='PROCESSING',state_hash=NULL,nonce_hash=NULL,pkce_verifier=NULL WHERE id=?",
                  item.id());
              return item;
            });
    boolean success = false;
    String handoffCode = AuthSecrets.randomToken();
    if (error == null && code != null && !code.isBlank() && code.length() <= 4096) {
      try {
        VerifiedIdentity identity = google.exchange(code, attempt.verifier(), attempt.nonceHash());
        success =
            transaction.execute(
                status ->
                    jdbc.update(
                            "UPDATE oauth_login_attempts SET status='COMPLETE',provider_subject=?,nickname=?,handoff_hash=?,handoff_expires_at=LEAST(expires_at,?) WHERE id=? AND status='PROCESSING' AND expires_at>?",
                            identity.subject(),
                            identity.nickname(),
                            AuthSecrets.hash(handoffCode),
                            Timestamp.from(clock.instant().plusSeconds(120)),
                            attempt.id(),
                            now())
                        == 1);
      } catch (ApiException ignored) {
        success = false;
      }
    }
    if (!success)
      jdbc.update(
          "UPDATE oauth_login_attempts SET status='FAILED',provider_subject=NULL,nickname=NULL,handoff_hash=NULL,handoff_expires_at=NULL WHERE id=?",
          attempt.id());
    var redirect =
        UriComponentsBuilder.fromUriString(attempt.redirectUri())
            .queryParam("loginId", attempt.id())
            .queryParam("status", success ? "SUCCESS" : "FAILED");
    if (success) redirect.queryParam("handoffCode", handoffCode);
    return redirect.build().encode().toUriString();
  }

  @Transactional
  public Tokens login(LoginRequest request) {
    if (!tokens.configured()) throw SessionTokenService.unavailable();
    var found =
        jdbc.query(
            "SELECT * FROM oauth_login_attempts WHERE id=? FOR UPDATE",
            this::attempt,
            request.loginId());
    if (found.isEmpty()) throw SessionTokenService.unauthorized();
    Attempt attempt = found.getFirst();
    if (!AuthSecrets.matches(request.loginSecret(), attempt.secretHash())
        || !attempt.expiresAt().isAfter(clock.instant())
        || "FAILED".equals(attempt.status())) throw SessionTokenService.unauthorized();
    if (!"COMPLETE".equals(attempt.status()))
      throw new ApiException(HttpStatus.CONFLICT, "LOGIN_PENDING", "Google 로그인을 먼저 완료해 주세요.");
    if (!AuthSecrets.matches(request.handoffCode(), attempt.handoffHash())
        || attempt.handoffExpiresAt() == null
        || !attempt.handoffExpiresAt().isAfter(clock.instant()))
      throw SessionTokenService.unauthorized();
    UUID userId =
        accounts.resolveIdentity(new VerifiedIdentity(attempt.subject(), attempt.nickname()));
    UUID sessionId = UUID.randomUUID();
    String refresh = AuthSecrets.randomToken();
    UserMe me = accounts.me(userId);
    Instant expiry =
        clock.instant().plusSeconds("ACTIVE".equals(me.status()) ? 30L * 86400 : 23L * 3600);
    jdbc.update(
        "INSERT INTO auth_sessions(id,user_id,refresh_token_hash,status,expires_at,created_at) VALUES (?,?,?,'ACTIVE',?,?)",
        sessionId,
        userId,
        AuthSecrets.hash(refresh),
        Timestamp.from(expiry),
        now());
    jdbc.update("DELETE FROM oauth_login_attempts WHERE id=?", attempt.id());
    return new Tokens(
        tokens.issue(userId, sessionId), refresh, SessionTokenService.ACCESS_SECONDS, me);
  }

  @Transactional
  public Tokens refresh(String refreshToken) {
    if (!tokens.configured()) throw SessionTokenService.unavailable();
    String hash = AuthSecrets.hash(refreshToken);
    var initial =
        jdbc.query("SELECT * FROM auth_sessions WHERE refresh_token_hash=?", this::session, hash);
    if (initial.isEmpty()) throw SessionTokenService.unauthorized();
    Session candidate = initial.getFirst();
    var user = accounts.user(candidate.userId(), true);
    accounts.ensureUsable(user);
    var locked =
        jdbc.query(
            "SELECT * FROM auth_sessions WHERE id=? FOR UPDATE", this::session, candidate.id());
    if (locked.isEmpty()) throw SessionTokenService.unauthorized();
    Session session = locked.getFirst();
    if (!hash.equals(session.refreshHash())
        || !"ACTIVE".equals(session.status())
        || !session.expiresAt().isAfter(clock.instant())) throw SessionTokenService.unauthorized();
    String next = AuthSecrets.randomToken();
    Instant expiry =
        "ACTIVE".equals(user.status())
            ? clock.instant().plusSeconds(30L * 86400)
            : session.expiresAt();
    jdbc.update(
        "UPDATE auth_sessions SET refresh_token_hash=?,expires_at=? WHERE id=?",
        AuthSecrets.hash(next),
        Timestamp.from(expiry),
        session.id());
    return new Tokens(
        tokens.issue(user.id(), session.id()),
        next,
        SessionTokenService.ACCESS_SECONDS,
        accounts.me(user.id()));
  }

  public AuthenticatedUser authenticate(String accessToken) {
    AuthenticatedUser principal = tokens.verify(accessToken);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.user_id=? AND s.status='ACTIVE' AND s.expires_at>? AND u.status IN ('ACTIVE','PENDING_ELIGIBILITY')",
            Integer.class,
            principal.sessionId(),
            principal.userId(),
            now());
    if (count == null || count != 1) throw SessionTokenService.unauthorized();
    return principal;
  }

  public void logout(AuthenticatedUser principal) {
    jdbc.update(
        "UPDATE auth_sessions SET status='REVOKED',revoked_at=? WHERE id=? AND user_id=? AND status='ACTIVE'",
        now(),
        principal.sessionId(),
        principal.userId());
  }

  @Scheduled(fixedDelayString = "${betchu.auth.cleanup-delay-ms:60000}", initialDelay = 0)
  @Transactional
  public void cleanup() {
    jdbc.update(
        "DELETE FROM oauth_login_attempts WHERE expires_at<=? OR handoff_expires_at<=?",
        now(),
        now());
    jdbc.update(
        "DELETE FROM users WHERE status='PENDING_ELIGIBILITY' AND created_at<=?",
        Timestamp.from(clock.instant().minusSeconds(23L * 3600)));
    jdbc.update(
        "UPDATE auth_sessions SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at<=?", now());
  }

  private Attempt attempt(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Attempt(
        rs.getObject("id", UUID.class),
        rs.getString("login_secret_hash"),
        rs.getString("redirect_uri"),
        rs.getString("status"),
        rs.getString("nonce_hash"),
        rs.getString("pkce_verifier"),
        rs.getString("provider_subject"),
        rs.getString("nickname"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getString("handoff_hash"),
        rs.getTimestamp("handoff_expires_at") == null
            ? null
            : rs.getTimestamp("handoff_expires_at").toInstant());
  }

  private Session session(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Session(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("refresh_token_hash"),
        rs.getString("status"),
        rs.getTimestamp("expires_at").toInstant());
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private ApiException invalidCallback() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "INVALID_OAUTH_CALLBACK", "만료되었거나 유효하지 않은 로그인 요청이에요. 다시 시작해 주세요.");
  }
}
