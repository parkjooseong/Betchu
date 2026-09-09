package com.betchu.backend.common;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class GameAccess {
  private final JdbcTemplate jdbc;

  public GameAccess(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID lockUser(UUID userId) {
    lockGlobal();
    if (userId == null)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "로그인이 필요해요.");
    List<UUID> current =
        jdbc.queryForList(
            """
        SELECT c.id FROM couples c JOIN couple_members m ON m.couple_id=c.id
        WHERE m.user_id=? AND m.status='ACTIVE' AND c.status='CONNECTED'
        """,
            UUID.class,
            userId);
    UUID coupleId = current.isEmpty() ? null : current.getFirst();
    if (coupleId != null)
      jdbc.queryForList("SELECT id FROM couples WHERE id=? FOR UPDATE", coupleId);
    List<String> states =
        jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE", String.class, userId);
    if (states.isEmpty() || !"ACTIVE".equals(states.getFirst()))
      throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "가입 확인을 먼저 완료해 주세요.");
    if (coupleId == null) return null;
    Integer eligibleMembers =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM couple_members m JOIN users u ON u.id=m.user_id
        WHERE m.couple_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
        """,
            Integer.class,
            coupleId);
    Integer activeMembers =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM couple_members WHERE couple_id=? AND status='ACTIVE'",
            Integer.class,
            coupleId);
    Boolean blocked =
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM user_blocks b
          JOIN couple_members m ON m.couple_id=? AND m.status='ACTIVE' AND m.user_id<>?
          WHERE (b.blocker_id=? AND b.blocked_id=m.user_id)
             OR (b.blocker_id=m.user_id AND b.blocked_id=?))
        """,
            Boolean.class,
            coupleId,
            userId,
            userId,
            userId);
    return Integer.valueOf(2).equals(activeMembers)
            && Integer.valueOf(2).equals(eligibleMembers)
            && !Boolean.TRUE.equals(blocked)
        ? coupleId
        : null;
  }

  public void lockRelationship(UUID coupleId) {
    lockGlobal();
    if (coupleId == null
        || jdbc.queryForList("SELECT id FROM couples WHERE id=? FOR UPDATE", coupleId).isEmpty())
      throw new ApiException(HttpStatus.NOT_FOUND, "COUPLE_NOT_FOUND", "확인할 수 없는 연결이에요.");
  }

  private void lockGlobal() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Game access locks require an active transaction");
    jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
  }
}
