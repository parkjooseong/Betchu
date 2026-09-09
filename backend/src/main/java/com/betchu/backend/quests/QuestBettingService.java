package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestLifecycleModels.*;

import com.betchu.backend.common.GameAccess;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuestBettingService {
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final JsonMapper mapper;

  public QuestBettingService(JdbcTemplate jdbc, GameAccess access, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.access = access;
    this.mapper = mapper;
  }

  @Transactional
  public Risk get(UUID user) {
    return view(user, scope(user));
  }

  @Transactional
  public Risk acknowledge(UUID user, UUID key, AcknowledgeInput input) {
    UUID couple = scope(user);
    if (key == null || input == null) throw invalid();
    List<Receipt> receipts =
        jdbc.query(
            "SELECT couple_id,generation,response_json FROM betting_rule_receipts WHERE user_id=? AND idempotency_key=?",
            (rs, n) -> new Receipt(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)),
            user,
            key);
    if (!receipts.isEmpty()) {
      Receipt old = receipts.getFirst();
      if (!couple.equals(old.couple())) throw notFound();
      if (old.generation() != input.expectedGeneration())
        throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 규칙 확인에 사용한 키예요.");
      return mapper.readValue(old.response(), Risk.class);
    }
    Risk current = view(user, couple);
    if (!current.suspended() || current.generation() != input.expectedGeneration())
      throw conflict("BETTING_RULES_CHANGED", "현재 정산 규칙 확인 상태를 다시 불러와 주세요.");
    Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
    jdbc.update(
        "INSERT INTO betting_rule_acknowledgments(couple_id,generation,user_id,acknowledged_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
        couple,
        current.generation(),
        user,
        now);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM betting_rule_acknowledgments a JOIN couples c ON c.id=a.couple_id JOIN couple_members m ON m.couple_id=c.id AND m.user_id=a.user_id AND m.status='ACTIVE' WHERE c.id=? AND a.generation=c.betting_suspension_generation AND a.acknowledged_at>=c.betting_suspended_at",
            Integer.class,
            couple);
    if (count == 2)
      jdbc.update(
          "UPDATE couples SET betting_suspended_at=NULL,betting_risk_window_started_at=(SELECT MAX(acknowledged_at) FROM betting_rule_acknowledgments WHERE couple_id=? AND generation=?) WHERE id=?",
          couple,
          current.generation(),
          couple);
    Risk response = view(user, couple);
    jdbc.update(
        "INSERT INTO betting_rule_receipts(user_id,idempotency_key,couple_id,generation,response_json) VALUES (?,?,?,?,?::jsonb)",
        user,
        key,
        couple,
        input.expectedGeneration(),
        mapper.writeValueAsString(response));
    return response;
  }

  private Risk view(UUID user, UUID couple) {
    return jdbc.queryForObject(
        """
        SELECT c.betting_suspension_generation,c.betting_suspended_at,
          (SELECT acknowledged_at FROM betting_rule_acknowledgments WHERE couple_id=c.id AND generation=c.betting_suspension_generation AND user_id=?) AS own_ack,
          EXISTS(SELECT 1 FROM betting_rule_acknowledgments WHERE couple_id=c.id AND generation=c.betting_suspension_generation AND user_id<>?) AS partner_ack,
          clock_timestamp() AS server_time FROM couples c WHERE c.id=?
        """,
        (rs, n) ->
            new Risk(
                couple,
                rs.getTimestamp(2) != null,
                rs.getInt(1),
                instant(rs.getTimestamp(2)),
                instant(rs.getTimestamp(3)),
                rs.getBoolean(4),
                rs.getTimestamp(5).toInstant()),
        user,
        user,
        couple);
  }

  private UUID scope(UUID user) {
    UUID couple = access.lockUser(user);
    if (couple == null) throw notFound();
    return couple;
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private record Receipt(UUID couple, int generation, String response) {}
}
