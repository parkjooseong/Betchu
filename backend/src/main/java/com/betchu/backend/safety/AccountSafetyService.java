package com.betchu.backend.safety;

import static com.betchu.backend.safety.SafetyModels.*;

import com.betchu.backend.auth.AuthSecrets;
import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameReceipts;
import com.betchu.backend.couples.CoupleService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountSafetyService {
  private static final Logger LOG = LoggerFactory.getLogger(AccountSafetyService.class);
  private final JdbcTemplate jdbc;
  private final CoupleService couples;
  private final GameReceipts receipts;
  private final TransactionTemplate transactions;
  private final com.betchu.backend.auth.AppleRevocationService revocations;

  public AccountSafetyService(
      JdbcTemplate jdbc,
      CoupleService couples,
      GameReceipts receipts,
      PlatformTransactionManager manager,
      com.betchu.backend.auth.AppleRevocationService revocations) {
    this.jdbc = jdbc;
    this.couples = couples;
    this.receipts = receipts;
    this.transactions = new TransactionTemplate(manager);
    this.revocations = revocations;
  }

  @Transactional
  public ReportReceipt report(UUID user, UUID key, ReportInput input) {
    lock(user);
    return receipts.execute(
        user,
        key,
        "REPORT",
        input,
        ReportReceipt.class,
        () -> {
          if (user.equals(input.reportedUserId())
              || jdbc.queryForList(
                      "SELECT a.couple_id FROM couple_members a JOIN couple_members b ON b.couple_id=a.couple_id WHERE a.user_id=? AND b.user_id=?",
                      user,
                      input.reportedUserId())
                  .isEmpty())
            throw new ApiException(
                HttpStatus.NOT_FOUND, "REPORT_TARGET_NOT_FOUND", "신고할 연결 기록을 찾을 수 없어요.");
          int count =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM user_reports WHERE reporter_id=? AND created_at>clock_timestamp()-interval '24 hours'",
                  Integer.class,
                  user);
          if (count >= 5)
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "REPORT_LIMIT",
                "오늘 보낸 신고를 처리하고 있어요. 잠시 후 다시 이용해 주세요.");
          if (!Set.of("HARASSMENT", "UNSAFE_REQUEST", "PRIVACY", "OTHER").contains(input.reason())
              || input.description() == null
              || input.description().isBlank()
              || input.description().length() > 2000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "신고 내용을 확인해 주세요.");
          UUID id = UUID.randomUUID();
          Instant now = now();
          jdbc.update(
              "INSERT INTO user_reports(id,reporter_id,reported_user_id,reason,description,created_at) VALUES (?,?,?,?,?,?)",
              id,
              user,
              input.reportedUserId(),
              input.reason(),
              input.description(),
              Timestamp.from(now));
          return new ReportReceipt(id, "RECEIVED", now);
        });
  }

  public record ReportTarget(UUID id, String nickname) {}

  @Transactional
  public List<ReportTarget> reportTargets(UUID user) {
    lock(user);
    return jdbc.query(
        "SELECT u.id,CASE WHEN bool_or(c.status='CONNECTED' AND a.status='ACTIVE' AND b.status='ACTIVE') THEN u.nickname ELSE '이전 파트너 ('||to_char(max(c.connected_at) AT TIME ZONE 'Asia/Seoul','YYYY-MM-DD')||')' END AS nickname FROM couple_members a JOIN couple_members b ON a.couple_id=b.couple_id JOIN couples c ON c.id=a.couple_id JOIN users u ON u.id=b.user_id WHERE a.user_id=? AND b.user_id<>? GROUP BY u.id,u.nickname ORDER BY max(c.connected_at) DESC,u.id",
        (rs, n) -> new ReportTarget(rs.getObject("id", UUID.class), rs.getString("nickname")),
        user,
        user);
  }

  @Transactional
  public Deletion delete(AuthenticatedUser principal, UUID key, DeleteInput input) {
    if (key == null || !"DELETE_MY_ACCOUNT".equals(input.confirmation()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "탈퇴 확인이 필요해요.");
    String status = lock(principal.userId());
    Boolean recent =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND status='ACTIVE' AND expires_at>clock_timestamp() AND created_at>clock_timestamp()-interval '10 minutes')",
            Boolean.class,
            principal.sessionId(),
            principal.userId());
    if (!Boolean.TRUE.equals(recent))
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "REAUTHENTICATION_REQUIRED",
          "계정을 보호하기 위해 다시 로그인한 뒤 10분 안에 탈퇴를 확인해 주세요.");
    if ("ACTIVE".equals(status)) couples.end(principal.userId(), key, false);
    Instant now = now();
    jdbc.update(
        "INSERT INTO account_deletion_jobs(user_id,idempotency_key,status,requested_at) VALUES (?,?,'PROCESSING',?) ON CONFLICT(user_id) DO NOTHING",
        principal.userId(),
        key,
        Timestamp.from(now));
    jdbc.update(
        "UPDATE users SET status='DELETION_PENDING',nickname='탈퇴한 사용자',profile_image=NULL,deleted_at=? WHERE id=?",
        Timestamp.from(now),
        principal.userId());
    jdbc.update(
        "UPDATE auth_sessions SET status='REVOKED',revoked_at=? WHERE user_id=?",
        Timestamp.from(now),
        principal.userId());
    return new Deletion("PROCESSING", now);
  }

  @Transactional
  public ExportPage export(UUID user, String section, String cursor) {
    lock(user);
    if (cursor != null && (cursor.length() > 80 || !cursor.matches("[a-zA-Z0-9-]+")))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "내보내기 위치를 확인해 주세요.");
    String columns, from;
    switch (section) {
      case "ACCOUNT" -> {
        columns = "id::text AS id,nickname,status,age_eligible_confirmed_at,created_at";
        from = "users WHERE id=?";
      }
      case "CONSENTS" -> {
        columns = "id::text AS id,policy_version_id,agreed_at,withdrawn_at";
        from = "policy_consents WHERE user_id=?";
      }
      case "LEDGER" -> {
        columns = "id::text AS id,entry_type,available_delta,locked_delta,created_at";
        from = "wallet_ledger WHERE user_id=?";
      }
      case "INVENTORY" -> {
        columns = "item_id AS id,fragments,acquired_at";
        from = "inventory_items WHERE user_id=?";
      }
      case "DRAWS" -> {
        columns =
            "id::text AS id,probability_version_id,item_id,payment,cost,duplicate,fragments_granted,created_at";
        from = "draw_results WHERE user_id=?";
      }
      case "MONSTERS" -> {
        columns = "id::text AS id,name,species,growth_stage,recognized_success_count,created_at";
        from = "monsters WHERE user_id=?";
      }
      case "PROGRESSION" -> {
        columns =
            "user_id::text AS id,level,current_level_exp,base_hp,base_attack,streak,updated_at";
        from = "user_progressions WHERE user_id=?";
      }
      case "EQUIPMENT" -> {
        columns = "slot AS id,item_id,row_version";
        from = "equipment_loadouts WHERE user_id=?";
      }
      case "RESCUE" -> {
        columns = "id::text AS id,status,started_at,available_at,expires_at,completed_at";
        from = "rescue_quests WHERE user_id=?";
      }
      case "REPORTS" -> {
        columns = "id::text AS id,reason,description,status,created_at";
        from = "user_reports WHERE reporter_id=?";
      }
      case "QUESTS" -> {
        columns =
            "q.id::text AS id,q.status,q.quest_type,COALESCE(d.title,v.title) AS title,COALESCE(d.success_criteria,v.success_criteria) AS success_criteria,COALESCE(d.due_at,v.due_at) AS due_at,COALESCE(d.result_at,v.result_at) AS result_at";
        from =
            "quests q JOIN couples c ON c.id=q.couple_id LEFT JOIN quest_drafts d ON d.quest_id=q.id LEFT JOIN quest_versions v ON v.id=COALESCE(q.approved_quest_version_id,q.current_quest_version_id) LEFT JOIN quest_settlements s ON s.quest_id=q.id WHERE q.creator_id=? AND (c.ended_at IS NULL OR c.ended_at>clock_timestamp()-interval '30 days') AND (s.settled_at IS NULL OR s.settled_at>clock_timestamp()-interval '30 days')";
      }
      default ->
          throw new ApiException(
              HttpStatus.BAD_REQUEST, "INVALID_EXPORT_SECTION", "내보내기 종류를 확인해 주세요.");
    }
    var rows =
        jdbc.queryForList(
            "SELECT * FROM (SELECT "
                + columns
                + " FROM "
                + from
                + ") own WHERE (CAST(? AS text) IS NULL OR id>?) ORDER BY id LIMIT 101",
            user,
            cursor,
            cursor);
    List<Map<String, Object>> normalized =
        rows.stream()
            .limit(100)
            .map(
                row -> {
                  Map<String, Object> result = new LinkedHashMap<>();
                  row.forEach(
                      (k, v) ->
                          result.put(
                              k, v instanceof Timestamp timestamp ? timestamp.toInstant() : v));
                  return result;
                })
            .toList();
    return new ExportPage(
        1,
        section,
        now(),
        normalized,
        rows.size() > 100 ? rows.get(99).get("id").toString() : null);
  }

  @Scheduled(
      fixedDelayString = "${betchu.safety.maintenance-delay-ms:60000}",
      initialDelayString = "${betchu.safety.maintenance-delay-ms:60000}")
  public void maintain() {
    for (UUID user :
        jdbc.queryForList(
            "SELECT user_id FROM account_deletion_jobs WHERE status='PROCESSING' ORDER BY requested_at LIMIT 50",
            UUID.class)) {
      try {
        transactions.executeWithoutResult(tx -> erase(user));
      } catch (RuntimeException error) {
        LOG.warn("Account deletion will retry ({})", error.getClass().getSimpleName());
      }
    }
    jdbc.update("DELETE FROM deleted_identity_cooldowns WHERE expires_at<=clock_timestamp()");
    jdbc.update("DELETE FROM user_reports WHERE expires_at<=clock_timestamp()");
  }

  private void erase(UUID user) {
    jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
    jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", user);
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM relationship_end_jobs j JOIN couple_members m ON m.couple_id=j.couple_id WHERE m.user_id=? AND j.status<>'COMPLETED')",
            Boolean.class,
            user))) return;
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM wallets WHERE user_id=? AND locked_coins<>0)",
            Boolean.class,
            user))) throw new IllegalStateException("Account settlement has not finished");
    for (String token :
        jdbc.queryForList(
            "SELECT encrypted_provider_token FROM oauth_identities WHERE user_id=? AND provider='APPLE' AND encrypted_provider_token IS NOT NULL",
            String.class,
            user)) revocations.enqueue(token);
    for (var identity :
        jdbc.queryForList(
            "SELECT provider,provider_subject FROM oauth_identities WHERE user_id=?", user))
      jdbc.update(
          "INSERT INTO deleted_identity_cooldowns(identity_hash,expires_at) VALUES (?,clock_timestamp()+interval '30 days') ON CONFLICT(identity_hash) DO UPDATE SET expires_at=EXCLUDED.expires_at",
          AuthSecrets.hash(identity.get("provider") + ":" + identity.get("provider_subject")));
    jdbc.update(
        "UPDATE quest_request_receipts SET response_json=NULL WHERE user_id=? OR quest_id IN (SELECT id FROM quests WHERE creator_id=?)",
        user,
        user);
    jdbc.update(
        "UPDATE tutorial_request_receipts SET response_json=NULL WHERE user_id=? OR quest_id IN (SELECT id FROM quests WHERE creator_id=?)",
        user,
        user);
    jdbc.update(
        "UPDATE quest_versions SET title='삭제된 퀘스트',success_criteria='삭제된 내용' WHERE quest_id IN (SELECT id FROM quests WHERE creator_id=?)",
        user);
    jdbc.update(
        "DELETE FROM quest_drafts WHERE quest_id IN (SELECT id FROM quests WHERE creator_id=?)",
        user);
    jdbc.update(
        "UPDATE quest_approvals SET request_message=NULL WHERE partner_id=? OR quest_id IN (SELECT id FROM quests WHERE creator_id=?)",
        user,
        user);
    jdbc.update("UPDATE monsters SET name='배츄' WHERE user_id=?", user);
    for (String table :
        List.of(
            "auth_sessions",
            "oauth_identities",
            "policy_consents",
            "starter_creation_receipts",
            "game_action_receipts",
            "equipment_loadouts",
            "inventory_items",
            "draw_pity_counters",
            "draw_tickets",
            "rescue_quests",
            "notification_events")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
    jdbc.update(
        "UPDATE users SET status='DELETED',age_eligible_confirmed_at=NULL WHERE id=?", user);
    jdbc.update(
        "UPDATE account_deletion_jobs SET status='COMPLETED',completed_at=clock_timestamp() WHERE user_id=?",
        user);
  }

  private String lock(UUID user) {
    jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
    var statuses =
        jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE", String.class, user);
    if (statuses.isEmpty()
        || !Set.of("ACTIVE", "PENDING_ELIGIBILITY").contains(statuses.getFirst()))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "로그인이 필요해요.");
    return statuses.getFirst();
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }
}
