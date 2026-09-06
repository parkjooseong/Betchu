package com.betchu.backend.auth;

import com.betchu.backend.auth.AuthModels.ConsentRequest;
import com.betchu.backend.auth.AuthModels.CurrentPolicies;
import com.betchu.backend.auth.AuthModels.Policy;
import com.betchu.backend.auth.AuthModels.UserMe;
import com.betchu.backend.auth.AuthModels.VerifiedIdentity;
import com.betchu.backend.common.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  record User(UUID id, String nickname, String status, Instant ageConfirmedAt) {}

  private final JdbcTemplate jdbc;
  private final PolicyService policies;
  private final Clock clock;

  public AccountService(JdbcTemplate jdbc, PolicyService policies, Clock clock) {
    this.jdbc = jdbc;
    this.policies = policies;
    this.clock = clock;
  }

  User user(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT id,nickname,status,age_eligible_confirmed_at FROM users WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) ->
                new User(
                    rs.getObject("id", UUID.class),
                    rs.getString("nickname"),
                    rs.getString("status"),
                    rs.getTimestamp("age_eligible_confirmed_at") == null
                        ? null
                        : rs.getTimestamp("age_eligible_confirmed_at").toInstant()),
            id)
        .stream()
        .findFirst()
        .orElseThrow(SessionTokenService::unauthorized);
  }

  @Transactional(readOnly = true)
  public UserMe me(UUID id) {
    return me(user(id, false), policies.current("ko-KR"));
  }

  private UserMe me(User user, CurrentPolicies current) {
    List<UUID> accepted =
        jdbc.query(
            "SELECT policy_version_id FROM policy_consents WHERE user_id = ? AND withdrawn_at IS NULL",
            (rs, row) -> rs.getObject(1, UUID.class),
            user.id());
    List<UUID> required =
        current.policies().stream().filter(Policy::required).map(Policy::id).toList();
    List<UUID> missing = required.stream().filter(id -> !accepted.contains(id)).toList();
    boolean evidence =
        current.policies().stream()
            .anyMatch(
                policy ->
                    "EVIDENCE_OPTIONAL".equals(policy.policyType())
                        && accepted.contains(policy.id()));
    List<long[]> wallets =
        jdbc.query(
            "SELECT available_coins,locked_coins FROM wallets WHERE user_id = ?",
            (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
            user.id());
    return new UserMe(
        user.id(),
        user.nickname(),
        user.status(),
        user.ageConfirmedAt() != null,
        required,
        missing,
        evidence,
        wallets.isEmpty() ? null : wallets.getFirst()[0],
        wallets.isEmpty() ? null : wallets.getFirst()[1]);
  }

  public void requireGeneralAccess(UUID userId) {
    CurrentPolicies current = policies.current("ko-KR");
    UserMe me = me(user(userId, false), current);
    if (!"ACTIVE".equals(me.status())
        || !me.ageEligible()
        || !current.ready()
        || !me.missingPolicyVersionIds().isEmpty())
      throw new ApiException(
          HttpStatus.FORBIDDEN, "ONBOARDING_REQUIRED", "연령 확인과 현재 필수 정책 동의를 완료해 주세요.");
  }

  @Transactional
  public UserMe age(UUID userId, Boolean eligible) {
    User user = user(userId, true);
    if (!Boolean.TRUE.equals(eligible)) {
      if (!"PENDING_ELIGIBILITY".equals(user.status()))
        throw new ApiException(
            HttpStatus.CONFLICT, "ACCOUNT_ALREADY_ACTIVE", "이미 가입한 계정은 가입 연령 응답으로 삭제할 수 없어요.");
      jdbc.update(
          "UPDATE auth_sessions SET status='REVOKED',revoked_at=? WHERE user_id=?", now(), userId);
      jdbc.update("DELETE FROM users WHERE id=? AND status='PENDING_ELIGIBILITY'", userId);
      return null;
    }
    ensureUsable(user);
    jdbc.update(
        "UPDATE users SET age_eligible_confirmed_at=COALESCE(age_eligible_confirmed_at,?) WHERE id=?",
        now(),
        userId);
    activateLocked(userId);
    return me(userId);
  }

  @Transactional
  public UserMe consent(UUID userId, String policyType, ConsentRequest request) {
    ensureUsable(user(userId, true));
    if (!"ko-KR".equals(request.locale()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_LOCALE", "현재 한국어 정책만 지원해요.");
    CurrentPolicies current = policies.currentLocked();
    Policy policy =
        current.policies().stream()
            .filter(
                item ->
                    item.id().equals(request.policyVersionId())
                        && item.policyType().equals(policyType)
                        && item.locale().equals(request.locale()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "POLICY_VERSION_CHANGED",
                        "정책이 변경되었어요. 현재 문서를 다시 확인해 주세요."));
    jdbc.update(
        "INSERT INTO policy_consents(id,user_id,policy_version_id,agreed_at) VALUES (?,?,?,?) ON CONFLICT(user_id,policy_version_id) DO UPDATE SET agreed_at=CASE WHEN policy_consents.withdrawn_at IS NULL THEN policy_consents.agreed_at ELSE EXCLUDED.agreed_at END,withdrawn_at=NULL",
        UUID.randomUUID(),
        userId,
        policy.id(),
        now());
    activateLocked(userId);
    return me(userId);
  }

  private void activateLocked(UUID userId) {
    User user = user(userId, true);
    CurrentPolicies current = policies.currentLocked();
    if (!"PENDING_ELIGIBILITY".equals(user.status())
        || user.ageConfirmedAt() == null
        || !current.ready()
        || !me(user, current).missingPolicyVersionIds().isEmpty()) return;
    jdbc.update(
        "INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", userId);
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'SIGNUP_GRANT',1000,0)",
        UUID.randomUUID(),
        userId);
    jdbc.update(
        "UPDATE users SET status='ACTIVE',signup_grant_issued_at=? WHERE id=?", now(), userId);
    jdbc.update(
        "UPDATE auth_sessions SET expires_at=? WHERE user_id=? AND status='ACTIVE' AND expires_at>?",
        Timestamp.from(clock.instant().plusSeconds(30L * 86400)),
        userId,
        now());
  }

  @Transactional
  UUID resolveIdentity(VerifiedIdentity identity) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        rs -> {},
        "GOOGLE:" + identity.subject());
    List<UUID> ids =
        jdbc.query(
            "SELECT user_id FROM oauth_identities WHERE provider='GOOGLE' AND provider_subject=?",
            (rs, row) -> rs.getObject(1, UUID.class),
            identity.subject());
    if (!ids.isEmpty()) {
      ensureUsable(user(ids.getFirst(), true));
      return ids.getFirst();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,created_at) VALUES (?,?,'PENDING_ELIGIBILITY',?)",
        id,
        identity.nickname(),
        now());
    jdbc.update(
        "INSERT INTO oauth_identities(user_id,provider,provider_subject) VALUES (?,'GOOGLE',?)",
        id,
        identity.subject());
    return id;
  }

  void ensureUsable(User user) {
    if (!List.of("ACTIVE", "PENDING_ELIGIBILITY").contains(user.status()))
      throw SessionTokenService.unauthorized();
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }
}
