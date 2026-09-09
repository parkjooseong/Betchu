package com.betchu.backend.game;

import com.betchu.backend.auth.AuthSecrets;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.game.GameModels.*;
import com.betchu.backend.starter.InvalidStarterSelectionException;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import com.betchu.backend.starter.StarterModels.Species;
import com.betchu.backend.starter.StarterService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MonsterService {
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final StarterService starters;
  private final JsonMapper mapper;

  public MonsterService(
      JdbcTemplate jdbc, GameAccess access, StarterService starters, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.access = access;
    this.starters = starters;
    this.mapper = mapper;
  }

  @Transactional
  public MonsterView create(UUID userId, UUID key, PreviewRequest request) {
    if (key == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "요청 키가 필요해요.");
    String name = normalizedName(request);
    UUID coupleId = access.lockUser(userId);
    String hash = AuthSecrets.hash(request.species().name() + "\n" + name);
    var receipts =
        jdbc.query(
            "SELECT request_hash,response_json::text FROM starter_creation_receipts WHERE user_id=? AND idempotency_key=?",
            (rs, row) -> new String[] {rs.getString(1), rs.getString(2)},
            userId,
            key);
    if (!receipts.isEmpty()) {
      if (!hash.equals(receipts.getFirst()[0]))
        throw new ApiException(
            HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 선택에 사용한 요청 키예요. 선택 내용을 확인해 주세요.");
      return mapper.readValue(receipts.getFirst()[1], MonsterView.class);
    }
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM monsters WHERE user_id=? AND acquisition_source='STARTER')",
            Boolean.class,
            userId);
    if (Boolean.TRUE.equals(exists))
      throw new ApiException(HttpStatus.CONFLICT, "STARTER_ALREADY_EXISTS", "이미 함께할 배츄를 선택했어요.");
    requireCouple(coupleId);
    var initial = starters.catalogue().initialStats();
    jdbc.update(
        "INSERT INTO user_progressions(user_id,level,current_level_exp,base_hp,base_attack,streak) VALUES (?,?,?,?,?,0)",
        userId,
        initial.level(),
        initial.exp(),
        initial.hp(),
        initial.atk());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO monsters(id,user_id,name,species,acquisition_source,growth_stage,recognized_success_count) VALUES (?,?,?,?,'STARTER','EGG',0)",
        id,
        userId,
        name,
        request.species().name());
    jdbc.update("INSERT INTO user_active_monsters(user_id,monster_id) VALUES (?,?)", userId, id);
    MonsterView result = activeMonster(userId);
    if (result == null) throw new IllegalStateException("Created starter has no active projection");
    jdbc.update(
        "INSERT INTO starter_creation_receipts(user_id,idempotency_key,request_hash,monster_id,response_json) VALUES (?,?,?,?,CAST(? AS jsonb))",
        userId,
        key,
        hash,
        id,
        mapper.writeValueAsString(result));
    return result;
  }

  @Transactional
  public MonsterResult me(UUID userId) {
    access.lockUser(userId);
    return new MonsterResult(activeMonster(userId));
  }

  @Transactional
  public MonsterResult partner(UUID userId) {
    UUID coupleId = access.lockUser(userId);
    requireCouple(coupleId);
    return new MonsterResult(activeMonster(partnerId(coupleId, userId)));
  }

  @Transactional
  public MonsterView rename(UUID userId, String name) {
    access.lockUser(userId);
    MonsterView current = activeMonster(userId);
    if (current == null)
      throw new ApiException(HttpStatus.NOT_FOUND, "MONSTER_NOT_FOUND", "아직 함께할 배츄를 선택하지 않았어요.");
    String normalized = normalizedName(new PreviewRequest(current.species(), name));
    jdbc.update(
        "UPDATE monsters SET name=? WHERE id=? AND user_id=?", normalized, current.id(), userId);
    return activeMonster(userId);
  }

  MonsterView activeMonster(UUID userId) {
    var monsters =
        jdbc.query(
            """
        SELECT m.*,p.level,p.current_level_exp,p.base_hp,p.base_attack,p.streak
        FROM user_active_monsters a JOIN monsters m ON m.id=a.monster_id AND m.user_id=a.user_id
        JOIN user_progressions p ON p.user_id=a.user_id WHERE a.user_id=?
        """,
            this::view,
            userId);
    return monsters.isEmpty() ? null : monsters.getFirst();
  }

  UUID partnerId(UUID coupleId, UUID userId) {
    return jdbc.queryForObject(
        "SELECT user_id FROM couple_members WHERE couple_id=? AND user_id<>? AND status='ACTIVE'",
        UUID.class,
        coupleId,
        userId);
  }

  static void requireCouple(UUID id) {
    if (id == null)
      throw new ApiException(HttpStatus.CONFLICT, "COUPLE_REQUIRED", "커플 연결을 완료한 뒤 함께 시작해 주세요.");
  }

  private String normalizedName(PreviewRequest request) {
    try {
      return starters.preview(request).name();
    } catch (InvalidStarterSelectionException invalid) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_STARTER_SELECTION", invalid.getMessage());
    }
  }

  private MonsterView view(ResultSet rs, int row) throws SQLException {
    GrowthStage stage = GrowthStage.valueOf(rs.getString("growth_stage"));
    int successes = rs.getInt("recognized_success_count"),
        level = rs.getInt("level"),
        hp = rs.getInt("base_hp"),
        attack = rs.getInt("base_attack");
    List<Milestone> reached = new ArrayList<>();
    if (rs.getTimestamp("hatched_at") != null) reached.add(Milestone.HATCH);
    if (rs.getTimestamp("intermediate_at") != null) reached.add(Milestone.SUCCESS_20);
    if (rs.getTimestamp("final_at") != null) reached.add(Milestone.SUCCESS_40);
    if (successes >= 60) reached.add(Milestone.SUCCESS_60);
    if (successes >= 80) reached.add(Milestone.SUCCESS_80);
    Milestone next =
        stage == GrowthStage.EGG
            ? Milestone.HATCH
            : successes < 20
                ? Milestone.SUCCESS_20
                : successes < 40
                    ? Milestone.SUCCESS_40
                    : successes < 60
                        ? Milestone.SUCCESS_60
                        : successes < 80 ? Milestone.SUCCESS_80 : null;
    return new MonsterView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        Species.valueOf(rs.getString("species")),
        stage,
        successes,
        next,
        List.copyOf(reached),
        jdbc.queryForList(
            "SELECT reward_code FROM monster_mastery_rewards WHERE monster_id=? ORDER BY kind",
            String.class,
            rs.getObject("id", UUID.class)),
        level,
        rs.getInt("current_level_exp"),
        level == 50 ? null : 80 + 20 * (level - 1),
        hp,
        attack,
        0,
        0,
        hp,
        attack,
        Math.addExact(Math.multiplyExact(hp, 5), Math.multiplyExact(attack, 20)),
        rs.getInt("streak"));
  }
}
