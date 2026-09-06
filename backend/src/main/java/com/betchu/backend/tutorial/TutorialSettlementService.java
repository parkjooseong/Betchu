package com.betchu.backend.tutorial;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.tutorial.TutorialModels.Settlement;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TutorialSettlementService {
  private static final Set<String> TERMINAL =
      Set.of(
          "SUCCESS",
          "FAILURE",
          "INVALID",
          "CANCELED",
          "CANCELED_RELATIONSHIP_ENDED",
          "REJECTED",
          "APPROVAL_EXPIRED");
  private final JdbcTemplate jdbc;
  private final GameAccess access;

  public TutorialSettlementService(JdbcTemplate jdbc, GameAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lockStake(UUID questId) {
    Quest quest = lockQuest(questId);
    if (quest.locked()) return;
    if (!"PENDING_APPROVAL".equals(quest.status())
        || !"CONNECTED".equals(quest.relationshipStatus()))
      throw new IllegalStateException(
          "Only a pending tutorial in a connected relationship can lock coins");
    String userStatus =
        jdbc.queryForObject(
            "SELECT status FROM users WHERE id=? FOR UPDATE", String.class, quest.creator());
    if (!"ACTIVE".equals(userStatus))
      throw new ApiException(
          HttpStatus.CONFLICT, "QUEST_REQUIRES_REVISION", "지금은 이 퀘스트를 시작할 수 없어요.");
    Wallet wallet = wallet(quest.creator());
    if (wallet.available() < 100)
      throw new ApiException(
          HttpStatus.CONFLICT, "QUEST_REQUIRES_REVISION", "지금은 이 퀘스트를 시작할 수 없어요.");
    Timestamp now = now();
    writeWallet(quest, wallet, -100, 100, "TUTORIAL_STAKE_LOCK", now);
    jdbc.update("UPDATE quests SET stake_locked_at=? WHERE id=?", now, quest.id());
  }

  /**
   * Lifecycle callers validate roles, selected revision, and deadlines before invoking settlement.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Settlement settle(UUID questId, String terminalStatus, String invalidReason) {
    if (!TERMINAL.contains(terminalStatus))
      throw new IllegalArgumentException("Not a tutorial terminal state");
    if ("INVALID".equals(terminalStatus) != (invalidReason != null))
      throw new IllegalArgumentException("Only invalid outcomes require an invalid reason");
    Quest quest = lockQuest(questId);
    if (TERMINAL.contains(quest.status())) return existingSettlement(questId);
    if (("SUCCESS".equals(terminalStatus) || "FAILURE".equals(terminalStatus))
        && (!quest.locked()
            || !"PENDING_FINAL_APPROVAL".equals(quest.status())
            || !"CONNECTED".equals(quest.relationshipStatus())))
      throw new IllegalStateException(
          "Final judgment requires an approved tutorial in a connected relationship");
    jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", quest.creator());
    Timestamp now = now();
    if (!quest.locked()) {
      finish(quest, terminalStatus, invalidReason, now);
      return null;
    }
    Wallet wallet = wallet(quest.creator());
    if (wallet.locked() < 100)
      throw new IllegalStateException("Tutorial stake is missing from the locked wallet");
    boolean success = "SUCCESS".equals(terminalStatus);
    ResultRow result = lockResult(quest);
    if ((success || "FAILURE".equals(terminalStatus)) && !terminalStatus.equals(result.selected()))
      throw new IllegalStateException("Final tutorial outcome does not match the selected result");

    UUID settlementId = UUID.randomUUID();
    UUID creditedMonster = null;
    String before = null, after = null;
    int grantedXp = 0;
    if (success) {
      Growth growth = grow(quest.creator(), now);
      creditedMonster = growth.monster();
      before = "EGG";
      after = "BABY";
      grantedXp = growth.xp();
    }
    int reward = success ? 100 : 0;
    UUID ledger = writeWallet(quest, wallet, 100L + reward, -100, "TUTORIAL_SETTLEMENT", now);
    jdbc.update(
        """
        INSERT INTO quest_settlements(id,quest_id,resolved_result,resolution_source,invalid_reason,
          stake,reward,xp,wallet_transaction_id,credited_monster_id,recognized_success_before,
          recognized_success_after,growth_stage_before,growth_stage_after,settled_at)
        VALUES (?,?,?,?,?,100,?,?,?,?,NULL,NULL,?,?,?)
        """,
        settlementId,
        quest.id(),
        terminalStatus,
        source(terminalStatus, invalidReason),
        invalidReason,
        reward,
        grantedXp,
        ledger,
        creditedMonster,
        before,
        after,
        now);
    if (success) {
      jdbc.update(
          """
          INSERT INTO monster_growth_milestone_claims(id,monster_id,milestone,source_quest_settlement_id,granted_at)
          VALUES (?,?,'HATCH',?,?)
          """,
          UUID.randomUUID(),
          creditedMonster,
          settlementId,
          now);
    }
    finishResult(quest, result, terminalStatus, invalidReason, now);
    finish(quest, terminalStatus, invalidReason, now);
    return existingSettlement(quest.id());
  }

  private Quest lockQuest(UUID id) {
    List<UUID> couples =
        jdbc.queryForList(
            "SELECT couple_id FROM quests WHERE id=? AND quest_type='TUTORIAL'", UUID.class, id);
    if (couples.isEmpty()) throw new IllegalArgumentException("Tutorial does not exist");
    access.lockRelationship(couples.getFirst());
    return jdbc.queryForObject(
        """
        SELECT q.id,q.creator_id,q.couple_id,q.status,q.stake_locked_at,c.status AS relationship_status
        FROM quests q JOIN couples c ON c.id=q.couple_id WHERE q.id=? AND q.quest_type='TUTORIAL' FOR UPDATE OF q
        """,
        (row, index) ->
            new Quest(
                row.getObject("id", UUID.class),
                row.getObject("creator_id", UUID.class),
                row.getObject("couple_id", UUID.class),
                row.getString("status"),
                row.getTimestamp("stake_locked_at") != null,
                row.getString("relationship_status")),
        id);
  }

  private Wallet wallet(UUID user) {
    return jdbc.queryForObject(
        "SELECT available_coins,locked_coins FROM wallets WHERE user_id=? FOR UPDATE",
        (row, index) -> new Wallet(row.getLong(1), row.getLong(2)),
        user);
  }

  private UUID writeWallet(
      Quest quest,
      Wallet wallet,
      long availableDelta,
      long lockedDelta,
      String phase,
      Timestamp now) {
    long available = Math.addExact(wallet.available(), availableDelta);
    long locked = Math.addExact(wallet.locked(), lockedDelta);
    if (available < 0 || locked < 0)
      throw new IllegalStateException("Tutorial wallet would become negative");
    jdbc.update(
        "UPDATE wallets SET available_coins=?,locked_coins=? WHERE user_id=?",
        available,
        locked,
        quest.creator());
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta,quest_id,
          available_balance_after,locked_balance_after,created_at) VALUES (?,?,?,?,?,?,?,?,?)
        """,
        id,
        quest.creator(),
        phase,
        availableDelta,
        lockedDelta,
        quest.id(),
        available,
        locked,
        now);
    return id;
  }

  private Growth grow(UUID user, Timestamp now) {
    int[] progress =
        jdbc.queryForObject(
            "SELECT level,current_level_exp,base_hp,base_attack FROM user_progressions WHERE user_id=? FOR UPDATE",
            (row, index) -> new int[] {row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4)},
            user);
    UUID monster =
        jdbc.queryForObject(
            "SELECT monster_id FROM user_active_monsters WHERE user_id=? FOR UPDATE",
            UUID.class,
            user);
    String stage =
        jdbc.queryForObject(
            "SELECT growth_stage FROM monsters WHERE id=? AND user_id=? FOR UPDATE",
            String.class,
            monster,
            user);
    if (!"EGG".equals(stage))
      throw new IllegalStateException("The first tutorial success must hatch an egg");
    int originalLevel = progress[0],
        level = originalLevel,
        xp = progress[1],
        granted = level < 50 ? 10 : 0;
    xp = Math.addExact(xp, granted);
    while (level < 50 && xp >= 80 + 20 * (level - 1)) {
      xp -= 80 + 20 * (level - 1);
      level++;
    }
    if (level == 50) xp = 0;
    jdbc.update(
        "UPDATE user_progressions SET level=?,current_level_exp=?,base_hp=?,base_attack=?,updated_at=? WHERE user_id=?",
        level,
        xp,
        Math.addExact(progress[2], 8 * (level - originalLevel)),
        Math.addExact(progress[3], 2 * (level - originalLevel)),
        now,
        user);
    jdbc.update("UPDATE monsters SET growth_stage='BABY',hatched_at=? WHERE id=?", now, monster);
    return new Growth(monster, granted);
  }

  private ResultRow lockResult(Quest quest) {
    List<ResultRow> rows =
        jdbc.query(
            "SELECT id,partner_id,selected_result FROM quest_partner_results WHERE quest_id=? FOR UPDATE",
            (row, index) ->
                new ResultRow(
                    row.getObject("id", UUID.class),
                    row.getObject("partner_id", UUID.class),
                    row.getString("selected_result")),
            quest.id());
    if (!rows.isEmpty()) return rows.getFirst();
    UUID partner =
        jdbc.queryForObject(
            "SELECT user_id FROM couple_members WHERE couple_id=? AND user_id<>?",
            UUID.class,
            quest.couple(),
            quest.creator());
    UUID result = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO quest_partner_results(id,quest_id,partner_id,selection_revision) VALUES (?,?,?,0)",
        result,
        quest.id(),
        partner);
    return new ResultRow(result, partner, null);
  }

  private void finishResult(
      Quest quest, ResultRow result, String status, String reason, Timestamp now) {
    String event;
    String decision = null;
    UUID actor = null;
    String selected = null;
    if (Set.of("SUCCESS", "FAILURE").contains(status)) {
      event = "FINAL_APPROVE";
      decision = "APPROVE";
      actor = result.partner();
      selected = status;
    } else if ("PARTNER_JUDGMENT_REJECTED".equals(reason)) {
      event = "JUDGMENT_REJECT";
      decision = "REJECT";
      actor = result.partner();
    } else if ("PARTNER_FINAL_APPROVAL_REJECTED".equals(reason)) {
      event = "FINAL_REJECT";
      decision = "REJECT";
      actor = result.partner();
    } else if ("RESULT_CONFIRMATION_TIMEOUT".equals(reason)) event = "TIMEOUT_INVALIDATE";
    else if ("RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL".equals(reason)
        || "CANCELED_RELATIONSHIP_ENDED".equals(status)) event = "RELATIONSHIP_INVALIDATE";
    else if ("CANCELED".equals(status)) event = "CANCELED";
    else event = "SYSTEM_INVALIDATE";
    jdbc.update(
        "UPDATE quest_partner_results SET final_decision=?,final_decided_at=?,invalid_reason=?,resolved_at=? WHERE id=?",
        decision,
        decision == null ? null : now,
        reason,
        now,
        result.id());
    UUID key =
        UUID.nameUUIDFromBytes(
            ("tutorial-terminal:" + quest.id()).getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        INSERT INTO quest_partner_result_events(id,quest_partner_result_id,actor_user_id,event_type,
          selected_result,idempotency_key,created_at) VALUES (?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        result.id(),
        actor,
        event,
        selected,
        key,
        now);
  }

  private void finish(Quest quest, String status, String reason, Timestamp now) {
    String canceled =
        "CANCELED_RELATIONSHIP_ENDED".equals(status)
            ? "RELATIONSHIP_ENDED"
            : "CANCELED".equals(status) ? "MUTUAL_CANCELLATION" : null;
    jdbc.update(
        "UPDATE quests SET status=?,invalid_reason=?,canceled_reason=?,row_version=row_version+1 WHERE id=?",
        status,
        reason,
        canceled,
        quest.id());
    jdbc.update(
        "UPDATE quest_cancel_requests SET status='EXPIRED',responded_at=? WHERE quest_id=? AND status='PENDING'",
        now,
        quest.id());
    jdbc.update(
        "UPDATE users SET tutorial_completed_at=COALESCE(tutorial_completed_at,?) WHERE id=?",
        now,
        quest.creator());
  }

  private Settlement existingSettlement(UUID questId) {
    return jdbc
        .query(
            "SELECT * FROM quest_settlements WHERE quest_id=?",
            TutorialSettlementService::view,
            questId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static Settlement view(ResultSet row, int index) throws SQLException {
    return new Settlement(
        row.getObject("id", UUID.class),
        row.getString("resolved_result"),
        row.getString("invalid_reason"),
        row.getInt("stake"),
        row.getInt("reward"),
        row.getInt("xp"),
        row.getObject("credited_monster_id", UUID.class),
        row.getObject("recognized_success_before", Integer.class),
        row.getObject("recognized_success_after", Integer.class),
        row.getString("growth_stage_before"),
        row.getString("growth_stage_after"),
        row.getTimestamp("settled_at").toInstant());
  }

  private static String source(String status, String reason) {
    if (Set.of("SUCCESS", "FAILURE").contains(status)) return "PARTNER_FINAL_APPROVAL";
    if ("CANCELED_RELATIONSHIP_ENDED".equals(status)
        || "RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL".equals(reason)) return "RELATIONSHIP_ENDED";
    if ("CANCELED".equals(status)) return "MUTUAL_CANCELLATION";
    if ("RESULT_CONFIRMATION_TIMEOUT".equals(reason)) return "TIMEOUT";
    if (reason != null && reason.startsWith("PARTNER_")) return "PARTNER_REJECTION";
    return "SYSTEM";
  }

  private Timestamp now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
  }

  private record Quest(
      UUID id,
      UUID creator,
      UUID couple,
      String status,
      boolean locked,
      String relationshipStatus) {}

  private record Wallet(long available, long locked) {}

  private record Growth(UUID monster, int xp) {}

  private record ResultRow(UUID id, UUID partner, String selected) {}
}
