package com.betchu.backend.couples;

import static com.betchu.backend.couples.CoupleModels.*;

import com.betchu.backend.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CoupleService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final InviteCodeCodec codes;

  public CoupleService(
      JdbcTemplate jdbc, PlatformTransactionManager manager, InviteCodeCodec codes) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.codes = codes;
  }

  public State me(UUID userId) {
    return locked(
        now -> {
          requireActive(userId);
          return state(userId);
        });
  }

  public CreatedInvite create(UUID userId, UUID key) {
    requireKey(key);
    return locked(
        now -> {
          requireActive(userId);
          List<Invite> replay =
              jdbc.query(
                  "SELECT * FROM couple_invites WHERE inviter_id = ? AND creation_key = ?",
                  CoupleService::invite,
                  userId,
                  key);
          if (!replay.isEmpty()) {
            Invite original = replay.getFirst();
            return new CreatedInvite(original.id(), null, original.expiresAt());
          }
          UUID oldId = slot(userId);
          if (oldId != null) {
            Invite previous = inviteById(oldId);
            if (!previous.inviterId().equals(userId)) throw conflict();
          }
          requireAvailable(userId, oldId);
          String code = codes.generate();
          String hash = codes.hash(code);
          if (oldId != null) finishInvite(oldId, "REVOKED", now);
          UUID id = UUID.randomUUID();
          Instant expiresAt = now.plus(24, ChronoUnit.HOURS);
          jdbc.update(
              """
          INSERT INTO couple_invites (id, inviter_id, code_hmac, creation_key, status, expires_at, created_at)
          VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
          """,
              id,
              userId,
              hash,
              key,
              timestamp(expiresAt),
              timestamp(now));
          jdbc.update(
              """
          INSERT INTO couple_pairing_slots (user_id, invite_id, role, expires_at)
          VALUES (?, ?, 'INVITER', ?)
          """,
              userId,
              id,
              timestamp(expiresAt));
          return new CreatedInvite(id, code, expiresAt);
        });
  }

  public void revoke(UUID userId, UUID inviteId) {
    locked(
        now -> {
          requireActive(userId);
          Invite invitation = ownedInvite(userId, inviteId);
          if (!invitation.inviterId().equals(userId)) throw notFound();
          if (invitation.status().equals("USED")) throw unavailable();
          if (invitation.open()) finishInvite(inviteId, "REVOKED", now);
          return null;
        });
  }

  public InvitePreview preview(UUID userId, String code) {
    recordAttempt(userId);
    String hash = codes.hash(code);
    return locked(
        now -> {
          requireActive(userId);
          Invite invitation = usableCode(userId, hash);
          requireAvailable(userId, invitation.id());
          return new InvitePreview(
              invitation.id(), profile(invitation.inviterId()), invitation.expiresAt());
        });
  }

  public PendingInvite join(UUID userId, String code) {
    recordAttempt(userId);
    String hash = codes.hash(code);
    return locked(
        now -> {
          requireActive(userId);
          Invite invitation = usableCode(userId, hash);
          requireAvailable(userId, invitation.id());
          if (invitation.status().equals("ACTIVE")) {
            jdbc.update(
                """
            INSERT INTO couple_pairing_slots (user_id, invite_id, role, expires_at)
            VALUES (?, ?, 'INVITEE', ?)
            """,
                userId,
                invitation.id(),
                timestamp(invitation.expiresAt()));
            jdbc.update(
                """
            UPDATE couple_invites SET status = 'PENDING_CONFIRMATION', claimed_by = ?, claimed_at = ?
            WHERE id = ?
            """,
                userId,
                timestamp(now),
                invitation.id());
          }
          return pending(userId, inviteById(invitation.id()));
        });
  }

  public State confirm(UUID userId, UUID inviteId) {
    return locked(
        now -> {
          requireActive(userId);
          Invite invitation = ownedInvite(userId, inviteId);
          if (invitation.status().equals("USED")) return state(userId);
          if (invitation.status().equals("EXPIRED")) {
            throw new ApiException(
                HttpStatus.CONFLICT, "INVITE_EXPIRED", "초대 시간이 지났어요. 새 코드로 다시 연결해 주세요.");
          }
          if (!invitation.status().equals("PENDING_CONFIRMATION")) throw unavailable();
          if (!isActive(invitation.inviterId())
              || !isActive(invitation.claimedBy())
              || blocked(invitation.inviterId(), invitation.claimedBy())) throw unavailable();
          requireAvailable(invitation.inviterId(), inviteId);
          requireAvailable(invitation.claimedBy(), inviteId);
          if (!inviteId.equals(slot(invitation.inviterId()))
              || !inviteId.equals(slot(invitation.claimedBy()))) {
            throw unavailable();
          }
          String column =
              userId.equals(invitation.inviterId())
                  ? "inviter_confirmed_at"
                  : "invitee_confirmed_at";
          // The column is chosen exclusively from the two literals above, never from request input.
          jdbc.update(
              "UPDATE couple_invites SET " + column + " = COALESCE(" + column + ", ?) WHERE id = ?",
              timestamp(now),
              inviteId);
          invitation = inviteById(inviteId);
          if (invitation.inviterConfirmedAt() != null && invitation.inviteeConfirmedAt() != null) {
            UUID coupleId = UUID.randomUUID();
            jdbc.update(
                """
            INSERT INTO couples (id, source_invite_id, status, connected_at) VALUES (?, ?, 'CONNECTED', ?)
            """,
                coupleId,
                inviteId,
                timestamp(now));
            for (UUID member : List.of(invitation.inviterId(), invitation.claimedBy())) {
              jdbc.update(
                  """
              INSERT INTO couple_members (couple_id, user_id, status, joined_at) VALUES (?, ?, 'ACTIVE', ?)
              """,
                  coupleId,
                  member,
                  timestamp(now));
            }
            jdbc.update(
                "UPDATE couple_invites SET status = 'USED', used_by = ?, used_at = ? WHERE id = ?",
                invitation.claimedBy(),
                timestamp(now),
                inviteId);
            jdbc.update("DELETE FROM couple_pairing_slots WHERE invite_id = ?", inviteId);
          }
          return state(userId);
        });
  }

  public void reject(UUID userId, UUID inviteId) {
    locked(
        now -> {
          requireActive(userId);
          Invite invitation = ownedInvite(userId, inviteId);
          if (invitation.status().equals("USED")) throw unavailable();
          if (invitation.open()) finishInvite(inviteId, "REJECTED", now);
          return null;
        });
  }

  public EndResult end(UUID userId, UUID key, boolean block) {
    requireKey(key);
    return locked(
        now -> {
          // Relationship locks precede user locks for the safety transition.
          UUID coupleId = activeCoupleId(userId);
          if (coupleId != null)
            jdbc.queryForList("SELECT id FROM couples WHERE id = ? FOR UPDATE", coupleId);
          requireActive(userId);
          String action = block ? "BLOCK" : "END";
          List<String> receipt =
              jdbc.queryForList(
                  "SELECT action FROM couple_action_receipts WHERE user_id = ? AND idempotency_key = ?",
                  String.class,
                  userId,
                  key);
          if (!receipt.isEmpty()) {
            if (!receipt.getFirst().equals(action)) {
              throw new ApiException(
                  HttpStatus.CONFLICT,
                  "IDEMPOTENCY_KEY_REUSED",
                  "다른 요청에 사용한 키예요. 새 요청으로 다시 시도해 주세요.");
            }
            List<String> states =
                jdbc.queryForList(
                    """
                SELECT j.status FROM couple_action_receipts r
                JOIN relationship_end_jobs j ON j.couple_id=r.couple_id
                WHERE r.user_id=? AND r.idempotency_key=?
                """,
                    String.class,
                    userId,
                    key);
            return new EndResult(
                states.isEmpty() || "COMPLETED".equals(states.getFirst())
                    ? "COMPLETED"
                    : "PROCESSING");
          }
          if (block && coupleId == null) throw conflict();
          UUID receiptCouple = coupleId;
          if (receiptCouple == null) {
            List<UUID> pending =
                jdbc.queryForList(
                    """
                SELECT j.couple_id FROM relationship_end_jobs j
                JOIN couple_members m ON m.couple_id=j.couple_id
                WHERE m.user_id=? AND j.status<>'COMPLETED' ORDER BY j.created_at DESC,j.id DESC LIMIT 1
                """,
                    UUID.class,
                    userId);
            if (!pending.isEmpty()) receiptCouple = pending.getFirst();
          }
          if (coupleId != null) {
            UUID partnerId =
                jdbc.queryForObject(
                    "SELECT user_id FROM couple_members WHERE couple_id = ? AND user_id <> ? AND status = 'ACTIVE'",
                    UUID.class,
                    coupleId,
                    userId);
            if (block) {
              jdbc.update(
                  """
              INSERT INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)
              ON CONFLICT (blocker_id, blocked_id) DO NOTHING
              """,
                  userId,
                  partnerId,
                  timestamp(now));
            }
            jdbc.update(
                "UPDATE couples SET status = 'ENDED', ended_at = ?, ended_by = ? WHERE id = ?",
                timestamp(now),
                userId,
                coupleId);
            jdbc.update(
                "UPDATE couple_members SET status = 'ENDED', left_at = ? WHERE couple_id = ? AND status = 'ACTIVE'",
                timestamp(now),
                coupleId);
            // Commit loss of relationship access before any fallible resource cleanup begins.
            // The shared advisory/couple locks exclude concurrent draft creation and modification.
            UUID jobId = UUID.randomUUID();
            int targets =
                jdbc.queryForObject(
                    "SELECT count(*) FROM quests WHERE couple_id=? AND (status='DRAFT' OR (quest_type='TUTORIAL' AND status IN ('PENDING_APPROVAL','ACTIVE','AWAITING_RESULT','PENDING_FINAL_APPROVAL')))",
                    Integer.class,
                    coupleId);
            jdbc.update(
                """
            INSERT INTO relationship_end_jobs
            (id, couple_id, initiated_by, status, target_resource_count, idempotency_key, created_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
                jobId,
                coupleId,
                userId,
                targets == 0 ? "COMPLETED" : "PROCESSING",
                targets,
                key,
                timestamp(now),
                targets == 0 ? timestamp(now) : null);
            jdbc.update(
                """
                INSERT INTO relationship_end_job_items(relationship_end_job_id,resource_type,resource_id,status)
                SELECT ?,'QUEST',id,'PENDING' FROM quests WHERE couple_id=?
                  AND (status='DRAFT' OR (quest_type='TUTORIAL' AND status IN ('PENDING_APPROVAL','ACTIVE','AWAITING_RESULT','PENDING_FINAL_APPROVAL')))
                """,
                jobId,
                coupleId);
          }
          jdbc.update(
              """
          INSERT INTO couple_action_receipts (user_id, idempotency_key, action, couple_id, created_at)
          VALUES (?, ?, ?, ?, ?)
          """,
              userId,
              key,
              action,
              receiptCouple,
              timestamp(now));
          return endResult(receiptCouple);
        });
  }

  public EndStatus endStatus(UUID userId) {
    return locked(
        now -> {
          requireActive(userId);
          List<EndJob> jobs =
              jdbc.query(
                  """
          SELECT j.id,j.status,j.created_at,j.completed_at,
            (SELECT count(*) FROM relationship_end_job_items i JOIN quests q ON q.id=i.resource_id
              WHERE i.relationship_end_job_id=j.id AND q.creator_id=m.user_id) AS target_resource_count,
            (SELECT count(*) FROM relationship_end_job_items i JOIN quests q ON q.id=i.resource_id
              WHERE i.relationship_end_job_id=j.id AND q.creator_id=m.user_id AND i.status='COMPLETED') AS processed_resource_count
          FROM relationship_end_jobs j JOIN couple_members m ON m.couple_id=j.couple_id
          WHERE m.user_id=? ORDER BY j.created_at DESC,j.id DESC LIMIT 1
          """,
                  (row, index) ->
                      new EndJob(
                          row.getObject("id", UUID.class),
                          "COMPLETED".equals(row.getString("status")) ? "COMPLETED" : "PROCESSING",
                          row.getInt("target_resource_count"),
                          row.getInt("processed_resource_count"),
                          instant(row, "created_at"),
                          instant(row, "completed_at")),
                  userId);
          return new EndStatus(jobs.isEmpty() ? null : jobs.getFirst());
        });
  }

  private EndResult endResult(UUID coupleId) {
    if (coupleId == null) return new EndResult("COMPLETED");
    String status =
        jdbc.queryForObject(
            "SELECT status FROM relationship_end_jobs WHERE couple_id=?", String.class, coupleId);
    return new EndResult("COMPLETED".equals(status) ? "COMPLETED" : "PROCESSING");
  }

  public void expireInvites() {
    locked(now -> null);
  }

  private State state(UUID userId) {
    UUID coupleId = activeCoupleId(userId);
    if (coupleId != null) {
      List<Couple> couples =
          jdbc.query(
              """
          SELECT c.id, c.connected_at, u.id AS partner_id, u.nickname, u.profile_image
          FROM couples c JOIN couple_members m ON m.couple_id = c.id
          JOIN users u ON u.id = m.user_id
          WHERE c.id = ? AND c.status = 'CONNECTED' AND m.status = 'ACTIVE'
          AND m.user_id <> ? AND u.status = 'ACTIVE'
          AND NOT EXISTS (SELECT 1 FROM user_blocks b
            WHERE (b.blocker_id = ? AND b.blocked_id = u.id) OR (b.blocker_id = u.id AND b.blocked_id = ?))
          """,
              (row, index) ->
                  new Couple(
                      row.getObject("id", UUID.class),
                      instant(row, "connected_at"),
                      new PublicProfile(
                          row.getObject("partner_id", UUID.class),
                          row.getString("nickname"),
                          row.getString("profile_image"))),
              coupleId,
              userId,
              userId,
              userId);
      return new State(couples.isEmpty() ? null : couples.getFirst(), null);
    }
    UUID inviteId = slot(userId);
    if (inviteId == null) return new State(null, null);
    Invite invitation = inviteById(inviteId);
    UUID other =
        userId.equals(invitation.inviterId()) ? invitation.claimedBy() : invitation.inviterId();
    if (other != null && (!isActive(other) || blocked(userId, other))) return new State(null, null);
    return new State(null, pending(userId, invitation));
  }

  private PendingInvite pending(UUID userId, Invite invitation) {
    boolean inviter = userId.equals(invitation.inviterId());
    UUID partner = inviter ? invitation.claimedBy() : invitation.inviterId();
    return new PendingInvite(
        invitation.id(),
        invitation.status(),
        inviter ? "INVITER" : "INVITEE",
        invitation.expiresAt(),
        partner == null ? null : profile(partner),
        (inviter ? invitation.inviterConfirmedAt() : invitation.inviteeConfirmedAt()) != null,
        (inviter ? invitation.inviteeConfirmedAt() : invitation.inviterConfirmedAt()) != null);
  }

  private Invite usableCode(UUID userId, String hash) {
    List<Invite> matches =
        jdbc.query("SELECT * FROM couple_invites WHERE code_hmac = ?", CoupleService::invite, hash);
    if (matches.isEmpty()) throw InviteCodeCodec.invalidCode();
    Invite invitation = matches.getFirst();
    if (!invitation.open()
        || invitation.inviterId().equals(userId)
        || (invitation.claimedBy() != null && !invitation.claimedBy().equals(userId))
        || !isActive(invitation.inviterId())
        || blocked(userId, invitation.inviterId())
        || activeCoupleId(invitation.inviterId()) != null
        || hasUnfinishedEnd(invitation.inviterId())
        || !invitation.id().equals(slot(invitation.inviterId())))
      throw InviteCodeCodec.invalidCode();
    return invitation;
  }

  private void requireAvailable(UUID userId, UUID allowedInvite) {
    UUID slot = slot(userId);
    if (activeCoupleId(userId) != null
        || hasUnfinishedEnd(userId)
        || (slot != null && !slot.equals(allowedInvite))) throw conflict();
  }

  private boolean hasUnfinishedEnd(UUID userId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM relationship_end_jobs j
          JOIN couple_members m ON m.couple_id = j.couple_id
          WHERE m.user_id = ? AND j.status <> 'COMPLETED')
        """,
            Boolean.class,
            userId));
  }

  private void requireActive(UUID userId) {
    if (userId == null)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
    List<String> states =
        jdbc.queryForList("SELECT status FROM users WHERE id = ? FOR UPDATE", String.class, userId);
    if (states.isEmpty() || !states.getFirst().equals("ACTIVE")) {
      throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "가입 확인을 먼저 완료해 주세요.");
    }
  }

  private boolean isActive(UUID userId) {
    return userId != null
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')",
                Boolean.class,
                userId));
  }

  private PublicProfile profile(UUID userId) {
    return jdbc.queryForObject(
        "SELECT id, nickname, profile_image FROM users WHERE id = ? AND status = 'ACTIVE'",
        (row, index) ->
            new PublicProfile(
                row.getObject("id", UUID.class),
                row.getString("nickname"),
                row.getString("profile_image")),
        userId);
  }

  private boolean blocked(UUID left, UUID right) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM user_blocks
        WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
        """,
            Boolean.class,
            left,
            right,
            right,
            left));
  }

  private UUID slot(UUID userId) {
    List<UUID> ids =
        jdbc.queryForList(
            "SELECT invite_id FROM couple_pairing_slots WHERE user_id = ?", UUID.class, userId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private UUID activeCoupleId(UUID userId) {
    List<UUID> ids =
        jdbc.queryForList(
            """
        SELECT m.couple_id FROM couple_members m JOIN couples c ON c.id = m.couple_id
        WHERE m.user_id = ? AND m.status = 'ACTIVE' AND c.status = 'CONNECTED'
        """,
            UUID.class,
            userId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private Invite ownedInvite(UUID userId, UUID inviteId) {
    List<Invite> invitations =
        jdbc.query("SELECT * FROM couple_invites WHERE id = ?", CoupleService::invite, inviteId);
    if (invitations.isEmpty()) throw notFound();
    Invite invitation = invitations.getFirst();
    if (!userId.equals(invitation.inviterId()) && !userId.equals(invitation.claimedBy()))
      throw notFound();
    return invitation;
  }

  private Invite inviteById(UUID inviteId) {
    return jdbc.queryForObject(
        "SELECT * FROM couple_invites WHERE id = ?", CoupleService::invite, inviteId);
  }

  private void finishInvite(UUID inviteId, String status, Instant now) {
    jdbc.update(
        "UPDATE couple_invites SET status = ?, revoked_at = ? WHERE id = ?",
        status,
        timestamp(now),
        inviteId);
    jdbc.update("DELETE FROM couple_pairing_slots WHERE invite_id = ?", inviteId);
  }

  private void recordAttempt(UUID userId) {
    // This transaction commits before code lookup: invalid guesses cannot roll their counters back.
    Integer attempts =
        transactions.execute(
            status -> {
              requireActive(userId);
              return jdbc.queryForObject(
                  """
          INSERT INTO couple_code_attempts (user_id, window_started_at, attempts) VALUES (?, clock_timestamp(), 1)
          ON CONFLICT (user_id) DO UPDATE SET
            attempts = CASE WHEN couple_code_attempts.window_started_at <= clock_timestamp() - interval '15 minutes'
              THEN 1 ELSE LEAST(couple_code_attempts.attempts + 1, 11) END,
            window_started_at = CASE WHEN couple_code_attempts.window_started_at <= clock_timestamp() - interval '15 minutes'
              THEN clock_timestamp() ELSE couple_code_attempts.window_started_at END
          RETURNING attempts
          """,
                  Integer.class,
                  userId);
            });
    if (attempts != null && attempts > 10) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "INVITE_RATE_LIMITED",
          "코드 확인을 여러 번 시도했어요. 15분 후 다시 시도해 주세요.");
    }
  }

  private <T> T locked(Function<Instant, T> operation) {
    Outcome<T> outcome =
        transactions.execute(
            status -> {
              // One PostgreSQL transaction lock serializes pairing across every application
              // instance.
              // Unique indexes remain the final guard; replace this bottleneck when traffic
              // requires it.
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              Instant now =
                  jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
              jdbc.update(
                  """
          UPDATE couple_invites SET status = 'EXPIRED'
          WHERE status IN ('ACTIVE', 'PENDING_CONFIRMATION') AND expires_at <= ?
          """,
                  timestamp(now));
              jdbc.update(
                  """
          DELETE FROM couple_pairing_slots WHERE invite_id IN
            (SELECT id FROM couple_invites WHERE status NOT IN ('ACTIVE', 'PENDING_CONFIRMATION'))
          """);
              try {
                return new Outcome<>(operation.apply(now), null);
              } catch (ApiException exception) {
                // Expected rejections still commit expiry cleanup; SQL/unexpected failures roll
                // back everything.
                return new Outcome<T>(null, exception);
              }
            });
    if (outcome == null)
      throw new IllegalStateException("Pairing transaction did not return a result");
    if (outcome.error() != null) throw outcome.error();
    return outcome.value();
  }

  private static void requireKey(UUID key) {
    if (key == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "요청 키가 필요해요.");
  }

  private static ApiException conflict() {
    return new ApiException(
        HttpStatus.CONFLICT, "PAIRING_CONFLICT", "현재 연결 또는 초대 상태를 확인한 뒤 다시 시도해 주세요.");
  }

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "확인할 수 없는 연결 요청이에요.");
  }

  private static ApiException unavailable() {
    return new ApiException(HttpStatus.CONFLICT, "INVITE_UNAVAILABLE", "이 연결 요청은 더 이상 사용할 수 없어요.");
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Invite invite(ResultSet row, int index) throws SQLException {
    return new Invite(
        row.getObject("id", UUID.class),
        row.getObject("inviter_id", UUID.class),
        row.getString("status"),
        instant(row, "expires_at"),
        row.getObject("claimed_by", UUID.class),
        instant(row, "inviter_confirmed_at"),
        instant(row, "invitee_confirmed_at"));
  }

  private record Invite(
      UUID id,
      UUID inviterId,
      String status,
      Instant expiresAt,
      UUID claimedBy,
      Instant inviterConfirmedAt,
      Instant inviteeConfirmedAt) {
    boolean open() {
      return status.equals("ACTIVE") || status.equals("PENDING_CONFIRMATION");
    }
  }

  private record Outcome<T>(T value, ApiException error) {}
}
