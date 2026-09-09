package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestLifecycleModels.*;

import com.betchu.backend.common.GameAccess;
import com.betchu.backend.tutorial.TutorialModels.Settlement;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestSettlementService {
  private static final Set<String> TERMINAL =
      Set.of(
          "SUCCESS",
          "FAILURE",
          "INVALID",
          "CANCELED",
          "CANCELED_RELATIONSHIP_ENDED",
          "REJECTED",
          "APPROVAL_EXPIRED",
          "DISCARDED");
  private final JdbcTemplate jdbc;
  private final GameAccess access;

  public QuestSettlementService(JdbcTemplate jdbc, GameAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Quote quote(UUID user, UUID id) {
    Quest q = lock(id);
    if (!q.creator().equals(user)) throw notFound();
    return quote(q, budget(q));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reserve(UUID id) {
    Quest q = lock(id);
    if (!"PENDING_APPROVAL".equals(q.status()) || !"CONNECTED".equals(q.relationship()))
      throw state();
    Quote quote = quote(q, budget(q));
    if (!quote.canApprove())
      throw conflict("QUEST_REQUIRES_REVISION", "작성자가 조건과 승인 가능한 범위를 다시 확인해야 해요.");
    Timestamp now = now();
    writeWallet(q, -q.stake(), q.stake(), "PERSONAL_STAKE_LOCK", now);
    jdbc.update(
        "UPDATE user_quest_counters SET active_count=active_count+1 WHERE user_id=?", q.creator());
    jdbc.update(
        "UPDATE daily_activity_budgets SET xp_slots_used=xp_slots_used+1 WHERE user_id=? AND budget_date_kst=?",
        q.creator(),
        q.date());
    if (q.stake() > 0)
      jdbc.update(
          "UPDATE daily_reward_budgets SET coin_slots_used=coin_slots_used+1,reward_reserved=reward_reserved+?,low_reward_reserved=low_reward_reserved+? WHERE user_id=? AND budget_date_kst=?",
          quote.expectedReward(),
          quote.expectedReward(),
          q.creator(),
          q.date());
    jdbc.update(
        "UPDATE quests SET budget_date_kst=?,reward_reserved_amount=?,activity_slot_reserved_at=?,coin_slot_reserved_at=?,stake_locked_at=? WHERE id=?",
        q.date(),
        quote.expectedReward(),
        now,
        q.stake() > 0 ? now : null,
        q.stake() > 0 ? now : null,
        id);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Settlement settle(UUID id, String status, String reason) {
    Quest q = lock(id);
    if (TERMINAL.contains(q.status())) return settlement(id);
    if (!TERMINAL.contains(status) || "DISCARDED".equals(status))
      throw new IllegalArgumentException("Unsupported personal outcome");
    if (("INVALID".equals(status)) != (reason != null))
      throw new IllegalArgumentException("Invalidation requires its reason");
    if (Set.of("SUCCESS", "FAILURE").contains(status)
        && (!q.approved()
            || !"PENDING_FINAL_APPROVAL".equals(q.status())
            || !"CONNECTED".equals(q.relationship()))) throw state();
    Timestamp now = now();
    if (!q.approved()) {
      if (!Set.of("REJECTED", "APPROVAL_EXPIRED", "CANCELED_RELATIONSHIP_ENDED").contains(status))
        throw state();
      finish(q, status, reason, now);
      return null;
    }
    if (!Set.of("SUCCESS", "FAILURE", "INVALID", "CANCELED", "CANCELED_RELATIONSHIP_ENDED")
        .contains(status)) throw state();
    budget(q);
    int reward = "SUCCESS".equals(status) ? q.reserved() : 0;
    UUID settlementId = UUID.randomUUID();
    Growth growth = "SUCCESS".equals(status) ? grow(q, now) : null;
    if ("FAILURE".equals(status))
      jdbc.update(
          "UPDATE user_progressions SET streak=0,updated_at=? WHERE user_id=?", now, q.creator());
    UUID ledger =
        writeWallet(
            q,
            "FAILURE".equals(status) ? 0 : q.stake() + reward,
            -q.stake(),
            "PERSONAL_SETTLEMENT",
            now);
    jdbc.update(
        "UPDATE user_quest_counters SET active_count=active_count-1 WHERE user_id=?", q.creator());
    if ("INVALID".equals(status))
      jdbc.update(
          "UPDATE daily_activity_budgets SET xp_slots_used=xp_slots_used-1 WHERE user_id=? AND budget_date_kst=?",
          q.creator(),
          q.date());
    if (q.stake() > 0)
      jdbc.update(
          """
        UPDATE daily_reward_budgets SET reward_reserved=reward_reserved-?,low_reward_reserved=low_reward_reserved-?,
          reward_issued=reward_issued+?,low_reward_issued=low_reward_issued+?,coin_slots_used=coin_slots_used-?
        WHERE user_id=? AND budget_date_kst=?
        """,
          q.reserved(),
          q.reserved(),
          reward,
          reward,
          "INVALID".equals(status) ? 1 : 0,
          q.creator(),
          q.date());
    jdbc.update(
        """
        INSERT INTO quest_settlements(id,quest_id,quest_type,resolved_result,resolution_source,invalid_reason,stake,reward,xp,
          wallet_transaction_id,credited_monster_id,recognized_success_before,recognized_success_after,growth_stage_before,growth_stage_after,settled_at)
        VALUES (?,?,'PERSONAL',?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        settlementId,
        id,
        status,
        source(status, reason),
        reason,
        q.stake(),
        reward,
        growth == null ? 0 : growth.xp(),
        ledger,
        growth == null ? null : growth.monster(),
        growth == null ? null : growth.before(),
        growth == null ? null : growth.after(),
        growth == null ? null : growth.stageBefore(),
        growth == null ? null : growth.stageAfter(),
        now);
    if (growth != null) milestones(q, growth, settlementId, now);
    resultAudit(q, status, reason, now);
    if ("INVALID".equals(status)
        && q.stake() > 0
        && Set.of(
                "PARTNER_JUDGMENT_REJECTED",
                "PARTNER_FINAL_APPROVAL_REJECTED",
                "RESULT_CONFIRMATION_TIMEOUT")
            .contains(reason)) incident(q, reason, now);
    finish(q, status, reason, now);
    return settlement(id);
  }

  public Settlement settlement(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM quest_settlements WHERE quest_id=?",
            (rs, index) ->
                new Settlement(
                    rs.getObject("id", UUID.class),
                    rs.getString("resolved_result"),
                    rs.getString("invalid_reason"),
                    rs.getInt("stake"),
                    rs.getInt("reward"),
                    rs.getInt("xp"),
                    rs.getObject("credited_monster_id", UUID.class),
                    rs.getObject("recognized_success_before", Integer.class),
                    rs.getObject("recognized_success_after", Integer.class),
                    rs.getString("growth_stage_before"),
                    rs.getString("growth_stage_after"),
                    rs.getTimestamp("settled_at").toInstant()),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Quest lock(UUID id) {
    List<UUID> couples =
        jdbc.queryForList(
            "SELECT couple_id FROM quests WHERE id=? AND quest_type='PERSONAL'", UUID.class, id);
    if (couples.isEmpty()) throw notFound();
    access.lockRelationship(couples.getFirst());
    Quest q =
        jdbc.queryForObject(
            """
        SELECT q.id,q.creator_id,q.couple_id,q.status,q.row_version,q.activity_slot_reserved_at,q.reward_reserved_amount,
          q.budget_date_kst,c.status AS relationship_status,c.betting_suspended_at,
          COALESCE(v.stake,d.stake,0) AS stake,COALESCE(v.difficulty,d.difficulty,1) AS difficulty,
          COALESCE(v.result_at,d.result_at,q.created_at) AS result_at
        FROM quests q JOIN couples c ON c.id=q.couple_id
        LEFT JOIN quest_versions v ON v.id=COALESCE(q.approved_quest_version_id,q.current_quest_version_id) AND q.status NOT IN ('DRAFT','CHANGE_REQUESTED')
        LEFT JOIN quest_drafts d ON d.quest_id=q.id WHERE q.id=? FOR UPDATE OF q
        """,
            (rs, n) ->
                new Quest(
                    id,
                    rs.getObject("creator_id", UUID.class),
                    rs.getObject("couple_id", UUID.class),
                    rs.getString("status"),
                    rs.getString("relationship_status"),
                    rs.getLong("row_version"),
                    rs.getTimestamp("activity_slot_reserved_at") != null,
                    rs.getInt("stake"),
                    rs.getInt("difficulty"),
                    rs.getInt("reward_reserved_amount"),
                    rs.getObject("budget_date_kst", LocalDate.class) == null
                        ? rs.getTimestamp("result_at")
                            .toInstant()
                            .atZone(ZoneId.of("Asia/Seoul"))
                            .toLocalDate()
                        : rs.getObject("budget_date_kst", LocalDate.class),
                    rs.getTimestamp("betting_suspended_at") != null),
            id);
    jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", q.creator());
    return q;
  }

  private Budget budget(Quest q) {
    jdbc.update(
        "INSERT INTO user_quest_counters(user_id) VALUES (?) ON CONFLICT DO NOTHING", q.creator());
    jdbc.update(
        "INSERT INTO daily_activity_budgets(user_id,budget_date_kst) VALUES (?,?) ON CONFLICT DO NOTHING",
        q.creator(),
        q.date());
    jdbc.update(
        "INSERT INTO daily_reward_budgets(user_id,budget_date_kst) VALUES (?,?) ON CONFLICT DO NOTHING",
        q.creator(),
        q.date());
    int active =
        jdbc.queryForObject(
            "SELECT active_count FROM user_quest_counters WHERE user_id=? FOR UPDATE",
            Integer.class,
            q.creator());
    int xp =
        jdbc.queryForObject(
            "SELECT xp_slots_used FROM daily_activity_budgets WHERE user_id=? AND budget_date_kst=? FOR UPDATE",
            Integer.class,
            q.creator(),
            q.date());
    return jdbc.queryForObject(
        "SELECT coin_slots_used,reward_reserved+reward_issued AS total,low_reward_reserved+low_reward_issued AS low FROM daily_reward_budgets WHERE user_id=? AND budget_date_kst=? FOR UPDATE",
        (rs, n) -> new Budget(active, xp, rs.getInt(1), rs.getInt(2), rs.getInt(3)),
        q.creator(),
        q.date());
  }

  private Quote quote(Quest q, Budget b) {
    long available =
        jdbc.queryForObject(
            "SELECT available_coins FROM wallets WHERE user_id=? FOR UPDATE",
            Long.class,
            q.creator());
    boolean ready =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users u JOIN user_active_monsters a ON a.user_id=u.id WHERE u.id=? AND u.status='ACTIVE' AND u.tutorial_completed_at IS NOT NULL)",
                Boolean.class,
                q.creator()));
    List<Integer> stakes = new ArrayList<>();
    if (ready && b.active() < 5 && b.xp() < 5) {
      stakes.add(0);
      if (!q.suspended() && b.coin() < 2 && available >= 100) stakes.add(100);
    }
    int reward =
        q.stake() == 0 ? 0 : Math.min(50, Math.min(300 - b.total(), 100 - b.low())) / 25 * 25;
    String reason =
        !ready
            ? "TUTORIAL_REQUIRED"
            : b.active() >= 5
                ? "ACTIVE_QUEST_LIMIT"
                : b.xp() >= 5
                    ? "DAILY_XP_LIMIT"
                    : q.stake() > 0 && q.suspended()
                        ? "BETTING_SUSPENDED"
                        : q.stake() > 0 && b.coin() >= 2
                            ? "DAILY_COIN_LIMIT"
                            : available < q.stake() ? "INSUFFICIENT_COINS" : null;
    return new Quote(
        q.id(),
        q.version(),
        q.date(),
        available,
        5 - b.active(),
        5 - b.xp(),
        2 - b.coin(),
        300 - b.total(),
        100 - b.low(),
        reward,
        List.copyOf(stakes),
        reason == null,
        reason);
  }

  private UUID writeWallet(
      Quest q, long availableDelta, long lockedDelta, String phase, Timestamp now) {
    long[] balances =
        jdbc.queryForObject(
            "SELECT available_coins,locked_coins FROM wallets WHERE user_id=? FOR UPDATE",
            (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)},
            q.creator());
    long available = Math.addExact(balances[0], availableDelta),
        locked = Math.addExact(balances[1], lockedDelta);
    if (available < 0 || locked < 0)
      throw new IllegalStateException("Quest wallet invariant failed");
    jdbc.update(
        "UPDATE wallets SET available_coins=?,locked_coins=? WHERE user_id=?",
        available,
        locked,
        q.creator());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta,quest_id,available_balance_after,locked_balance_after,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        q.creator(),
        phase,
        availableDelta,
        lockedDelta,
        q.id(),
        available,
        locked,
        now);
    return id;
  }

  private Growth grow(Quest q, Timestamp now) {
    int[] p =
        jdbc.queryForObject(
            "SELECT level,current_level_exp,base_hp,base_attack,streak FROM user_progressions WHERE user_id=? FOR UPDATE",
            (rs, n) ->
                new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)},
            q.creator());
    UUID monster =
        jdbc.queryForObject(
            "SELECT monster_id FROM user_active_monsters WHERE user_id=? FOR UPDATE",
            UUID.class,
            q.creator());
    Monster m =
        jdbc.queryForObject(
            "SELECT recognized_success_count,growth_stage,species FROM monsters WHERE id=? AND user_id=? FOR UPDATE",
            (rs, n) -> new Monster(rs.getInt(1), rs.getString(2), rs.getString(3)),
            monster,
            q.creator());
    int count = Math.addExact(m.count(), 1),
        streak = Math.addExact(p[4], 1),
        base = new int[] {0, 10, 20, 40, 70}[q.difficulty()];
    int xp =
        p[0] == 50
            ? 0
            : base * (streak >= 10 ? 130 : streak >= 5 ? 120 : streak >= 3 ? 110 : 100) / 100;
    int level = p[0], remaining = Math.addExact(p[1], xp);
    while (level < 50 && remaining >= 80 + 20 * (level - 1)) {
      remaining -= 80 + 20 * (level - 1);
      level++;
    }
    if (level == 50) remaining = 0;
    String stage = count >= 40 ? "FINAL" : count >= 20 ? "INTERMEDIATE" : "BABY";
    if ("FINAL".equals(m.stage()) || "INTERMEDIATE".equals(m.stage()) && count < 20)
      stage = m.stage();
    jdbc.update(
        "UPDATE user_progressions SET level=?,current_level_exp=?,base_hp=?,base_attack=?,streak=?,updated_at=? WHERE user_id=?",
        level,
        remaining,
        Math.addExact(p[2], 8 * (level - p[0])),
        Math.addExact(p[3], 2 * (level - p[0])),
        streak,
        now,
        q.creator());
    jdbc.update(
        "UPDATE monsters SET recognized_success_count=?,growth_stage=?,hatched_at=COALESCE(hatched_at,?),intermediate_at=CASE WHEN ?>=20 THEN COALESCE(intermediate_at,CAST(? AS timestamptz)) ELSE intermediate_at END,final_at=CASE WHEN ?>=40 THEN COALESCE(final_at,CAST(? AS timestamptz)) ELSE final_at END WHERE id=?",
        count,
        stage,
        now,
        count,
        now,
        count,
        now,
        monster);
    return new Growth(monster, m.count(), count, m.stage(), stage, m.species(), xp);
  }

  private void milestones(Quest q, Growth g, UUID settlement, Timestamp now) {
    int[] thresholds = {1, 20, 40, 60, 80};
    String[] names = {"HATCH", "SUCCESS_20", "SUCCESS_40", "SUCCESS_60", "SUCCESS_80"};
    for (int i = 0; i < thresholds.length; i++)
      if (g.after() >= thresholds[i]) {
        int created =
            jdbc.update(
                "INSERT INTO monster_growth_milestone_claims(id,monster_id,milestone,source_quest_settlement_id,granted_at) VALUES (?,?,?,?,?) ON CONFLICT(monster_id,milestone) DO NOTHING",
                UUID.randomUUID(),
                g.monster(),
                names[i],
                settlement,
                now);
        if (created > 0 && thresholds[i] == 60)
          for (String kind : List.of("AURA", "TITLE", "ACCESSORY"))
            jdbc.update(
                "INSERT INTO monster_mastery_rewards(id,user_id,monster_id,species,kind,reward_code,granted_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id,species,kind) DO NOTHING",
                UUID.randomUUID(),
                q.creator(),
                g.monster(),
                g.species(),
                kind,
                g.species() + "_MASTERY_" + kind,
                now);
        if (created > 0 && thresholds[i] == 80) event(q, "SUCCESS_80", q.creator(), now);
      }
  }

  private void resultAudit(Quest q, String status, String reason, Timestamp now) {
    UUID partner =
        jdbc.queryForObject(
            "SELECT user_id FROM couple_members WHERE couple_id=? AND user_id<>?",
            UUID.class,
            q.couple(),
            q.creator());
    jdbc.update(
        "INSERT INTO quest_partner_results(id,quest_id,partner_id) VALUES (?,?,?) ON CONFLICT(quest_id) DO NOTHING",
        UUID.randomUUID(),
        q.id(),
        partner);
    UUID result =
        jdbc.queryForObject(
            "SELECT id FROM quest_partner_results WHERE quest_id=? FOR UPDATE", UUID.class, q.id());
    boolean finalApproval = Set.of("SUCCESS", "FAILURE").contains(status),
        rejected = reason != null && reason.startsWith("PARTNER_");
    String event =
        finalApproval
            ? "FINAL_APPROVE"
            : "PARTNER_JUDGMENT_REJECTED".equals(reason)
                ? "JUDGMENT_REJECT"
                : "PARTNER_FINAL_APPROVAL_REJECTED".equals(reason)
                    ? "FINAL_REJECT"
                    : "RESULT_CONFIRMATION_TIMEOUT".equals(reason)
                        ? "TIMEOUT_INVALIDATE"
                        : source(status, reason).equals("RELATIONSHIP_ENDED")
                            ? "RELATIONSHIP_INVALIDATE"
                            : "CANCELED".equals(status) ? "CANCELED" : "SYSTEM_INVALIDATE";
    jdbc.update(
        "UPDATE quest_partner_results SET final_decision=?,final_decided_at=?,invalid_reason=?,resolved_at=? WHERE id=?",
        finalApproval ? "APPROVE" : rejected ? "REJECT" : null,
        finalApproval || rejected ? now : null,
        reason,
        now,
        result);
    jdbc.update(
        "INSERT INTO quest_partner_result_events(id,quest_partner_result_id,actor_user_id,event_type,selected_result,idempotency_key,created_at) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        result,
        finalApproval || rejected ? partner : null,
        event,
        finalApproval ? status : null,
        UUID.nameUUIDFromBytes(("personal-terminal:" + q.id()).getBytes(StandardCharsets.UTF_8)),
        now);
  }

  private void incident(Quest q, String reason, Timestamp now) {
    jdbc.update(
        "INSERT INTO couple_betting_incidents(quest_id,couple_id,reason,occurred_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
        q.id(),
        q.couple(),
        reason,
        now);
    int count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM couple_betting_incidents i JOIN couples c ON c.id=i.couple_id WHERE c.id=? AND i.occurred_at>=?::timestamptz-interval '7 days' AND (c.betting_risk_window_started_at IS NULL OR i.occurred_at>c.betting_risk_window_started_at)",
            Integer.class,
            q.couple(),
            now);
    if (count >= 3)
      jdbc.update(
          "UPDATE couples SET betting_suspension_generation=betting_suspension_generation+1,betting_suspended_at=? WHERE id=? AND betting_suspended_at IS NULL",
          now,
          q.couple());
  }

  private void finish(Quest q, String status, String reason, Timestamp now) {
    jdbc.update(
        "UPDATE quests SET status=?,invalid_reason=?,canceled_reason=?,row_version=row_version+1 WHERE id=?",
        status,
        reason,
        "CANCELED".equals(status)
            ? "MUTUAL_CANCELLATION"
            : "CANCELED_RELATIONSHIP_ENDED".equals(status) ? "RELATIONSHIP_ENDED" : null,
        q.id());
    jdbc.update(
        "UPDATE quest_cancel_requests SET status='EXPIRED',responded_at=? WHERE quest_id=? AND status='PENDING'",
        now,
        q.id());
    event(q, status, null, now);
  }

  private void event(Quest q, String type, UUID recipient, Timestamp now) {
    jdbc.update(
        "INSERT INTO quest_outbox(id,quest_id,event_type,row_version,recipient_id,created_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID(),
        q.id(),
        type,
        q.version() + 1,
        recipient,
        now);
  }

  private String source(String status, String reason) {
    return Set.of("SUCCESS", "FAILURE").contains(status)
        ? "PARTNER_FINAL_APPROVAL"
        : "CANCELED_RELATIONSHIP_ENDED".equals(status)
                || "RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL".equals(reason)
            ? "RELATIONSHIP_ENDED"
            : "CANCELED".equals(status)
                ? "MUTUAL_CANCELLATION"
                : "RESULT_CONFIRMATION_TIMEOUT".equals(reason)
                    ? "TIMEOUT"
                    : reason != null && reason.startsWith("PARTNER_")
                        ? "PARTNER_REJECTION"
                        : "SYSTEM";
  }

  private RuntimeException state() {
    return conflict("QUEST_STATE_CONFLICT", "퀘스트 상태가 변경됐어요. 다시 확인해 주세요.");
  }

  private Timestamp now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
  }

  private record Quest(
      UUID id,
      UUID creator,
      UUID couple,
      String status,
      String relationship,
      long version,
      boolean approved,
      int stake,
      int difficulty,
      int reserved,
      LocalDate date,
      boolean suspended) {}

  private record Budget(int active, int xp, int coin, int total, int low) {}

  private record Monster(int count, String stage, String species) {}

  private record Growth(
      UUID monster,
      int before,
      int after,
      String stageBefore,
      String stageAfter,
      String species,
      int xp) {}
}
