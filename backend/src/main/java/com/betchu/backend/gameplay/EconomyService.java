package com.betchu.backend.gameplay;

import static com.betchu.backend.gameplay.EconomyModels.*;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.common.GameReceipts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EconomyService {
  private static final List<String> RARITIES =
      List.of("COMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
  private static final List<String> SLOTS = List.of("WEAPON", "ARMOR", "ACCESSORY");
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final GameReceipts receipts;
  private final DrawRandom random;
  private final WeeklyBossService weeks;
  private final GameTime time;

  public EconomyService(
      JdbcTemplate jdbc,
      GameAccess access,
      GameReceipts receipts,
      DrawRandom random,
      WeeklyBossService weeks,
      GameTime time) {
    this.jdbc = jdbc;
    this.access = access;
    this.receipts = receipts;
    this.random = random;
    this.weeks = weeks;
    this.time = time;
  }

  @Transactional
  public Economy get(UUID user) {
    access.lockUser(user);
    return state(user);
  }

  @Transactional
  public Draw draw(UUID user, UUID key, DrawInput input) {
    access.lockUser(user);
    return receipts.execute(
        user,
        key,
        "DRAW",
        input,
        Draw.class,
        () -> {
          Odds odds =
              odds(user).stream()
                  .filter(o -> o.type().equals(input.type()))
                  .findFirst()
                  .orElseThrow(() -> bad("INVALID_DRAW", "뽑기 종류를 확인해 주세요."));
          if (!odds.versionId().equals(input.probabilityVersionId()))
            throw conflict("ODDS_CHANGED", "확률표가 바뀌었어요. 현재 확률을 다시 확인해 주세요.");
          int cost = odds.cost();
          if ("TICKET".equals(input.payment())) {
            if (!"NORMAL".equals(input.type())) throw bad("INVALID_TICKET", "일반 뽑기에만 사용할 수 있어요.");
            if (jdbc.update(
                    "UPDATE draw_tickets SET normal_tickets=normal_tickets-1 WHERE user_id=? AND normal_tickets>0",
                    user)
                != 1) throw conflict("TICKET_REQUIRED", "사용할 수 있는 일반 뽑기권이 없어요.");
            cost = 0;
          } else if ("COINS".equals(input.payment())) {
            if (jdbc.update(
                    "UPDATE wallets SET available_coins=available_coins-? WHERE user_id=? AND available_coins>=?",
                    cost,
                    user,
                    cost)
                != 1) throw conflict("INSUFFICIENT_COINS", "사용 가능한 코인이 부족해요.");
          } else throw bad("INVALID_PAYMENT", "결제 수단을 확인해 주세요.");
          boolean pity = odds.pityThreshold() > 0 && odds.misses() + 1 >= odds.pityThreshold();
          String rarity = pity ? odds.pityRarity() : roll(odds.weights(), random.nextInt(10000));
          var candidates =
              jdbc.query(
                  "SELECT i.*,i.id AS definition_id FROM item_definitions i JOIN draw_probability_items p ON p.item_id=i.id WHERE p.probability_version_id=? AND i.rarity=? ORDER BY i.id",
                  this::item,
                  odds.versionId(),
                  rarity);
          if (candidates.isEmpty())
            throw new IllegalStateException("Published draw pool is incomplete");
          Item item = candidates.get(random.nextInt(candidates.size()));
          int after =
              odds.pityThreshold() == 0
                      || RARITIES.indexOf(rarity) >= RARITIES.indexOf(odds.pityRarity())
                  ? 0
                  : odds.misses() + 1;
          jdbc.update(
              "INSERT INTO draw_pity_counters(user_id,draw_type,misses) VALUES (?,?,?) ON CONFLICT(user_id,draw_type) DO UPDATE SET misses=EXCLUDED.misses",
              user,
              input.type(),
              after);
          boolean duplicate =
              jdbc.update(
                      "INSERT INTO inventory_items(user_id,item_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                      user,
                      item.id())
                  == 0;
          int fragments = duplicate ? item.duplicateFragments() : 0;
          if (duplicate)
            jdbc.update(
                "UPDATE inventory_items SET fragments=fragments+? WHERE user_id=? AND item_id=?",
                fragments,
                user,
                item.id());
          UUID id = UUID.randomUUID();
          Instant now = now();
          jdbc.update(
              """
          INSERT INTO draw_results(id,user_id,probability_version_id,item_id,payment,cost,pity_applied,pity_before,pity_after,duplicate,fragments_granted,created_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
          """,
              id,
              user,
              odds.versionId(),
              item.id(),
              input.payment(),
              cost,
              pity,
              odds.misses(),
              after,
              duplicate,
              fragments,
              Timestamp.from(now));
          ledger(user, id, "DRAW", -cost);
          return new Draw(
              id,
              odds.versionId(),
              input.payment(),
              cost,
              item,
              pity,
              odds.misses(),
              after,
              duplicate,
              fragments,
              now);
        });
  }

  static String roll(List<Integer> weights, int roll) {
    if (roll < 0
        || roll >= 10000
        || weights.size() != 5
        || weights.stream().mapToInt(Integer::intValue).sum() != 10000)
      throw new IllegalArgumentException("Invalid probability input");
    int cumulative = 0;
    for (int i = 0; i < weights.size(); i++) {
      cumulative += weights.get(i);
      if (roll < cumulative) return RARITIES.get(i);
    }
    throw new IllegalStateException("Incomplete probability table");
  }

  @Transactional
  public Economy equip(UUID user, UUID key, EquipInput input) {
    access.lockUser(user);
    return receipts.execute(
        user,
        key,
        "EQUIP",
        input,
        Economy.class,
        () -> {
          if (!SLOTS.contains(input.slot())) throw bad("INVALID_SLOT", "장비 칸을 확인해 주세요.");
          if (weeks.power(user) == null) throw conflict("MONSTER_REQUIRED", "먼저 함께할 배츄를 선택해 주세요.");
          weeks.beforePowerChange(user);
          jdbc.update(
              "INSERT INTO equipment_loadouts(user_id,slot) VALUES (?,?) ON CONFLICT DO NOTHING",
              user,
              input.slot());
          if (input.itemId() != null
              && jdbc.queryForList(
                      "SELECT i.id FROM inventory_items own JOIN item_definitions i ON i.id=own.item_id WHERE own.user_id=? AND i.id=? AND i.slot=?",
                      user,
                      input.itemId(),
                      input.slot())
                  .isEmpty()) throw bad("ITEM_NOT_OWNED", "이 칸에 장착할 수 있는 보유 아이템이 아니에요.");
          if (jdbc.update(
                  "UPDATE equipment_loadouts SET item_id=?,row_version=row_version+1 WHERE user_id=? AND slot=? AND row_version=?",
                  input.itemId(),
                  user,
                  input.slot(),
                  input.rowVersion())
              != 1) throw conflict("EQUIPMENT_CHANGED", "장비가 바뀌었어요. 현재 장비를 다시 확인해 주세요.");
          return state(user);
        });
  }

  @Transactional
  public RescueStatus rescue(UUID user) {
    access.lockUser(user);
    return rescueState(user, now());
  }

  @Transactional
  public RescueStatus startRescue(UUID user, UUID key) {
    access.lockUser(user);
    return receipts.execute(
        user,
        key,
        "RESCUE_START",
        List.of(),
        RescueStatus.class,
        () -> {
          Instant now = now();
          RescueStatus state = rescueState(user, now);
          if (!state.eligible() || state.quest() != null && "ACTIVE".equals(state.quest().status()))
            throw conflict("RESCUE_UNAVAILABLE", "현재 구조 퀘스트를 시작할 수 없어요. 안내를 다시 확인해 주세요.");
          jdbc.update(
              "INSERT INTO rescue_quests(id,user_id,status,started_at,available_at,expires_at) VALUES (?,?,'ACTIVE',?,?,?)",
              UUID.randomUUID(),
              user,
              Timestamp.from(now),
              Timestamp.from(now.plusSeconds(60)),
              Timestamp.from(now.plus(1, ChronoUnit.DAYS)));
          return rescueState(user, now);
        });
  }

  @Transactional
  public RescueStatus completeRescue(UUID user, UUID key, CompleteRescue input) {
    access.lockUser(user);
    return receipts.execute(
        user,
        key,
        "RESCUE_COMPLETE",
        input,
        RescueStatus.class,
        () -> {
          Instant now = now();
          RescueStatus state = rescueState(user, now);
          Rescue quest = state.quest();
          if (!input.completed()
              || quest == null
              || !quest.id().equals(input.questId())
              || !"ACTIVE".equals(quest.status()))
            throw conflict("RESCUE_NOT_ACTIVE", "진행 중인 구조 퀘스트를 확인해 주세요.");
          if (now.isBefore(quest.availableAt()))
            throw conflict("RESCUE_TOO_EARLY", "1분 동안 잠시 쉬어 주세요. 완료 시간이 되면 보상을 받을 수 있어요.");
          if (!state.eligible())
            throw conflict("RESCUE_UNAVAILABLE", "현재 보상 조건에 해당하지 않아요. 잔액과 이용 기한을 확인해 주세요.");
          jdbc.update(
              "UPDATE rescue_quests SET status='COMPLETED',completed_at=? WHERE id=?",
              Timestamp.from(now),
              quest.id());
          jdbc.update(
              "UPDATE wallets SET available_coins=available_coins+100 WHERE user_id=?", user);
          ledger(user, quest.id(), "RESCUE_REWARD", 100);
          return rescueState(user, now);
        });
  }

  @Transactional
  public WalletHistory history(UUID user, UUID cursor) {
    access.lockUser(user);
    var entries =
        jdbc.query(
            """
        SELECT id,entry_type,available_delta,locked_delta,created_at FROM wallet_ledger
        WHERE user_id=? AND (CAST(? AS uuid) IS NULL OR (created_at,id)<
          (SELECT created_at,id FROM wallet_ledger WHERE id=? AND user_id=?))
        ORDER BY created_at DESC,id DESC LIMIT 51
        """,
            (rs, n) ->
                new LedgerEntry(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getTimestamp(5).toInstant()),
            user,
            cursor,
            cursor,
            user);
    return new WalletHistory(
        entries.stream().limit(50).toList(), entries.size() > 50 ? entries.get(49).id() : null);
  }

  private Economy state(UUID user) {
    var wallet =
        jdbc.queryForObject(
            "SELECT available_coins,locked_coins FROM wallets WHERE user_id=?",
            (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)},
            user);
    var inventory =
        jdbc.query(
            "SELECT i.*,i.id AS definition_id,own.fragments FROM inventory_items own JOIN item_definitions i ON i.id=own.item_id WHERE own.user_id=? ORDER BY i.id",
            (rs, n) -> new OwnedItem(item(rs, n), rs.getLong("fragments")),
            user);
    var slots = new ArrayList<Slot>();
    for (String slot : SLOTS) {
      var values =
          jdbc.query(
              "SELECT slot,item_id,row_version FROM equipment_loadouts WHERE user_id=? AND slot=?",
              (rs, n) -> new Slot(rs.getString(1), rs.getString(2), rs.getLong(3)),
              user,
              slot);
      slots.add(values.isEmpty() ? new Slot(slot, null, 0) : values.getFirst());
    }
    var recent =
        jdbc.query(
            "SELECT r.id AS result_id,r.*,i.*,i.id AS definition_id FROM draw_results r JOIN item_definitions i ON i.id=r.item_id WHERE r.user_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT 30",
            (rs, n) ->
                new Draw(
                    rs.getObject("result_id", UUID.class),
                    rs.getString("probability_version_id"),
                    rs.getString("payment"),
                    rs.getInt("cost"),
                    item(rs, n),
                    rs.getBoolean("pity_applied"),
                    rs.getInt("pity_before"),
                    rs.getInt("pity_after"),
                    rs.getBoolean("duplicate"),
                    rs.getInt("fragments_granted"),
                    rs.getTimestamp("created_at").toInstant()),
            user);
    int tickets =
        jdbc.queryForObject(
            "SELECT COALESCE((SELECT normal_tickets FROM draw_tickets WHERE user_id=?),0)",
            Integer.class,
            user);
    var catalog =
        jdbc.query(
            "SELECT i.*,i.id AS definition_id FROM item_definitions i ORDER BY i.id", this::item);
    return new Economy(
        wallet[0], wallet[1], tickets, catalog, odds(user), inventory, slots, recent);
  }

  private List<Odds> odds(UUID user) {
    return jdbc.query(
        """
        SELECT DISTINCT ON(v.draw_type) v.*,COALESCE(p.misses,0) AS misses
        FROM draw_probability_versions v LEFT JOIN draw_pity_counters p ON p.user_id=? AND p.draw_type=v.draw_type
        WHERE v.effective_at<=clock_timestamp() ORDER BY v.draw_type,v.effective_at DESC,v.version DESC
        """,
        (rs, n) ->
            new Odds(
                rs.getString("id"),
                rs.getString("draw_type"),
                rs.getInt("cost"),
                List.of(
                    rs.getInt("common_weight"),
                    rs.getInt("rare_weight"),
                    rs.getInt("epic_weight"),
                    rs.getInt("legendary_weight"),
                    rs.getInt("mythic_weight")),
                rs.getInt("pity_threshold"),
                rs.getString("pity_rarity"),
                rs.getInt("misses"),
                jdbc.queryForList(
                    "SELECT item_id FROM draw_probability_items WHERE probability_version_id=? ORDER BY item_id",
                    String.class,
                    rs.getString("id"))),
        user);
  }

  private RescueStatus rescueState(UUID user, Instant now) {
    jdbc.update(
        "UPDATE rescue_quests SET status='EXPIRED' WHERE user_id=? AND status='ACTIVE' AND expires_at<=?",
        user,
        Timestamp.from(now));
    var rewards =
        jdbc.queryForList(
            "SELECT completed_at FROM rescue_quests WHERE user_id=? AND status='COMPLETED' AND completed_at>? ORDER BY completed_at DESC",
            Timestamp.class,
            user,
            Timestamp.from(now.minus(30, ChronoUnit.DAYS)));
    Instant next = null;
    if (!rewards.isEmpty()) next = rewards.getFirst().toInstant().plus(7, ChronoUnit.DAYS);
    if (rewards.size() >= 2) {
      Instant monthly = rewards.get(1).toInstant().plus(30, ChronoUnit.DAYS);
      if (next == null || monthly.isAfter(next)) next = monthly;
    }
    var wallet =
        jdbc.queryForObject(
            "SELECT available_coins,locked_coins FROM wallets WHERE user_id=?",
            (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)},
            user);
    String reason =
        wallet[1] > 0
            ? "LOCKED_COINS"
            : wallet[0] >= 100
                ? "BALANCE_SUFFICIENT"
                : next != null && now.isBefore(next) ? "COOLDOWN" : null;
    var rows =
        jdbc.query(
            "SELECT * FROM rescue_quests WHERE user_id=? ORDER BY started_at DESC,id DESC LIMIT 1",
            (rs, n) ->
                new Rescue(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    "화면에서 눈을 떼고 1분 쉬기",
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("available_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("completed_at") == null
                        ? null
                        : rs.getTimestamp("completed_at").toInstant()),
            user);
    return new RescueStatus(
        now, reason == null, reason, next, rows.isEmpty() ? null : rows.getFirst());
  }

  private Item item(ResultSet rs, int row) throws SQLException {
    String id = rs.getString("definition_id");
    return new Item(
        id,
        rs.getString("name"),
        rs.getString("rarity"),
        rs.getString("slot"),
        rs.getInt("hp_bonus"),
        rs.getInt("attack_bonus"),
        rs.getInt("hp_bonus") * 5 + rs.getInt("attack_bonus") * 20,
        rs.getInt("duplicate_fragments"));
  }

  private void ledger(UUID user, UUID source, String type, int amount) {
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta,available_balance_after,locked_balance_after) SELECT ?,?,?,?,0,available_coins,locked_coins FROM wallets WHERE user_id=?",
        source,
        user,
        type,
        amount,
        user);
  }

  private Instant now() {
    return time.now();
  }

  private ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  private ApiException bad(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }
}
