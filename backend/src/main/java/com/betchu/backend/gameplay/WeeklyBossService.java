package com.betchu.backend.gameplay;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.common.GameReceipts;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyBossService {
  public record Power(int base, int total) {}

  public record Member(
      UUID userId,
      int baselinePower,
      int currentPower,
      int weeklyGrowth,
      boolean joined,
      Integer finalPower) {}

  public record Boss(
      UUID id,
      LocalDate weekStartKst,
      String status,
      long rowVersion,
      int requiredPower,
      int chemistry,
      Instant opensAt,
      Instant closesAt,
      Member self,
      Member partner) {}

  public record Week(Instant serverTime, Boss boss) {}

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final GameReceipts receipts;
  private final GameTime time;
  private final org.springframework.transaction.support.TransactionTemplate transactions;
  private UUID maintenanceCursor;

  public WeeklyBossService(
      JdbcTemplate jdbc,
      GameAccess access,
      GameReceipts receipts,
      GameTime time,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.receipts = receipts;
    this.time = time;
    this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
  }

  public Power power(UUID user) {
    var values =
        jdbc.query(
            """
      SELECT p.base_hp*5+p.base_attack*20 AS base,
        p.base_hp*5+p.base_attack*20+COALESCE((SELECT SUM(i.hp_bonus*5+i.attack_bonus*20)
        FROM equipment_loadouts l JOIN item_definitions i ON i.id=l.item_id WHERE l.user_id=p.user_id),0) AS total
      FROM user_progressions p JOIN user_active_monsters a ON a.user_id=p.user_id WHERE p.user_id=?
      """,
            (rs, n) -> new Power(rs.getInt(1), rs.getInt(2)),
            user);
    return values.isEmpty() ? null : values.getFirst();
  }

  /** Invoke before every power mutation; lazy rollover then preserves the Monday baseline. */
  @Transactional
  public void beforePowerChange(UUID user) {
    UUID couple = access.lockUser(user);
    if (couple != null) ensure(couple, now());
  }

  @Transactional
  public Week get(UUID user) {
    UUID couple = access.lockUser(user);
    Instant now = now();
    if (couple == null) return new Week(now, null);
    UUID id = ensure(couple, now);
    if (id == null) return new Week(now, null);
    advance(id, couple, now);
    return new Week(now, view(id, user));
  }

  @Transactional
  public Week join(UUID user, UUID key, UUID id, long revision) {
    UUID couple = access.lockUser(user);
    if (couple == null
        || jdbc.queryForList(
                "SELECT id FROM weekly_coop_bosses WHERE id=? AND couple_id=?", id, couple)
            .isEmpty())
      throw new ApiException(HttpStatus.NOT_FOUND, "BOSS_NOT_FOUND", "현재 연결의 보스를 찾을 수 없어요.");
    Instant now = now();
    advance(id, couple, now);
    return receipts.execute(
        user,
        key,
        "BOSS_JOIN",
        List.of(id, revision),
        Week.class,
        () -> {
          Boss boss = view(id, user);
          if (boss.rowVersion() != revision)
            throw conflict("BOSS_CHANGED", "참여 상태가 바뀌었어요. 새로 확인해 주세요.");
          if (!List.of("AVAILABLE", "WAITING_PARTNER").contains(boss.status())
              || now.isBefore(boss.opensAt())
              || !now.isBefore(boss.closesAt())
              || boss.chemistry() < 10)
            throw conflict("BOSS_NOT_AVAILABLE", "주말에 케미 게이지 10칸을 채운 뒤 참여할 수 있어요.");
          if (boss.self().joined()) throw conflict("ALREADY_JOINED", "이미 참여했어요.");
          boolean first =
              jdbc.queryForObject(
                  "SELECT first_user_id=? FROM weekly_coop_bosses WHERE id=?",
                  Boolean.class,
                  user,
                  id);
          jdbc.update(
              "UPDATE weekly_coop_bosses SET "
                  + (first ? "first_joined_at" : "second_joined_at")
                  + "=?,status='WAITING_PARTNER',row_version=row_version+1 WHERE id=?",
              Timestamp.from(now),
              id);
          if (boss.partner().joined()) {
            Power own = power(user), partner = power(boss.partner().userId());
            boolean cleared = own.total() + partner.total() >= boss.requiredPower();
            jdbc.update(
                "UPDATE weekly_coop_bosses SET status=?,first_final_power=?,second_final_power=?,decided_at=?,row_version=row_version+1 WHERE id=?",
                cleared ? "CLEARED" : "FAILED",
                first ? own.total() : partner.total(),
                first ? partner.total() : own.total(),
                Timestamp.from(now),
                id);
            if (cleared)
              for (UUID recipient : List.of(user, boss.partner().userId()))
                jdbc.update(
                    "INSERT INTO draw_tickets(user_id,normal_tickets) VALUES (?,1) ON CONFLICT(user_id) DO UPDATE SET normal_tickets=draw_tickets.normal_tickets+1",
                    recipient);
          }
          return new Week(now, view(id, user));
        });
  }

  @Scheduled(
      fixedDelayString = "${betchu.gameplay.maintenance-delay-ms:60000}",
      initialDelayString = "${betchu.gameplay.maintenance-delay-ms:60000}")
  public void maintain() {
    var batch =
        jdbc.queryForList(
            "SELECT id FROM couples WHERE status='CONNECTED' AND (CAST(? AS uuid) IS NULL OR id>?) ORDER BY id LIMIT 100",
            UUID.class,
            maintenanceCursor,
            maintenanceCursor);
    for (UUID couple : batch) {
      try {
        transactions.executeWithoutResult(
            tx -> {
              access.lockRelationship(couple);
              if (!Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT status='CONNECTED' FROM couples WHERE id=?", Boolean.class, couple)))
                return;
              Instant now = now();
              UUID id = ensure(couple, now);
              if (id != null) advance(id, couple, now);
            });
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(WeeklyBossService.class)
            .warn("Weekly boss maintenance will retry ({})", error.getClass().getSimpleName());
      }
    }
    maintenanceCursor = batch.size() < 100 ? null : batch.getLast();
    jdbc.update(
        """
        UPDATE weekly_coop_bosses b SET status='CANCELED_RELATIONSHIP_ENDED',row_version=row_version+1
        FROM couples c WHERE b.couple_id=c.id AND c.status<>'CONNECTED' AND b.status IN ('LOCKED','AVAILABLE','WAITING_PARTNER')
        """);
  }

  private UUID ensure(UUID couple, Instant now) {
    LocalDate week =
        now.atZone(KST).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    jdbc.update(
        "UPDATE weekly_coop_bosses SET status='EXPIRED',row_version=row_version+1 WHERE couple_id=? AND week_start_kst<? AND status IN ('LOCKED','AVAILABLE','WAITING_PARTNER')",
        couple,
        week);
    var existing =
        jdbc.queryForList(
            "SELECT id FROM weekly_coop_bosses WHERE couple_id=? AND week_start_kst=?",
            UUID.class,
            couple,
            week);
    if (!existing.isEmpty()) return existing.getFirst();
    var users =
        jdbc.queryForList(
            "SELECT m.user_id FROM couple_members m JOIN user_active_monsters a ON a.user_id=m.user_id WHERE m.couple_id=? AND m.status='ACTIVE' ORDER BY m.user_id",
            UUID.class,
            couple);
    if (users.size() != 2) return null;
    Power first = power(users.get(0)), second = power(users.get(1));
    UUID id = UUID.randomUUID();
    int required = ((first.base() + second.base()) * 12 + 999) / 1000 * 100;
    jdbc.update(
        """
        INSERT INTO weekly_coop_bosses(id,couple_id,week_start_kst,first_user_id,second_user_id,
        first_base_power,second_base_power,first_baseline_power,second_baseline_power,required_power,status)
        VALUES (?,?,?,?,?,?,?,?,?,?,'LOCKED')
        """,
        id,
        couple,
        week,
        users.get(0),
        users.get(1),
        first.base(),
        second.base(),
        first.total(),
        second.total(),
        required);
    return id;
  }

  private void advance(UUID id, UUID couple, Instant now) {
    LocalDate week =
        jdbc.queryForObject(
            "SELECT week_start_kst FROM weekly_coop_bosses WHERE id=?", LocalDate.class, id);
    Instant open = week.plusDays(5).atStartOfDay(KST).toInstant(),
        close = week.plusDays(7).atStartOfDay(KST).toInstant();
    if (!now.isBefore(close))
      jdbc.update(
          "UPDATE weekly_coop_bosses SET status='EXPIRED',row_version=row_version+1 WHERE id=? AND status IN ('LOCKED','AVAILABLE','WAITING_PARTNER')",
          id);
    else if (!now.isBefore(open) && chemistry(couple, week) >= 10)
      jdbc.update(
          "UPDATE weekly_coop_bosses SET status='AVAILABLE',row_version=row_version+1 WHERE id=? AND status='LOCKED'",
          id);
  }

  private int chemistry(UUID couple, LocalDate week) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM quest_settlements s JOIN quests q ON q.id=s.quest_id
        WHERE q.couple_id=? AND s.quest_type='PERSONAL' AND s.resolved_result IN ('SUCCESS','FAILURE')
        AND s.settled_at>=? AND s.settled_at<?
        """,
        Integer.class,
        couple,
        Timestamp.from(week.atStartOfDay(KST).toInstant()),
        Timestamp.from(week.plusDays(7).atStartOfDay(KST).toInstant()));
  }

  private Boss view(UUID id, UUID user) {
    return jdbc.queryForObject(
        "SELECT * FROM weekly_coop_bosses WHERE id=?",
        (rs, n) -> {
          LocalDate week = rs.getObject("week_start_kst", LocalDate.class);
          UUID first = rs.getObject("first_user_id", UUID.class),
              second = rs.getObject("second_user_id", UUID.class);
          Member a =
              member(
                  first,
                  rs.getInt("first_baseline_power"),
                  rs.getTimestamp("first_joined_at") != null,
                  (Integer) rs.getObject("first_final_power"));
          Member b =
              member(
                  second,
                  rs.getInt("second_baseline_power"),
                  rs.getTimestamp("second_joined_at") != null,
                  (Integer) rs.getObject("second_final_power"));
          return new Boss(
              id,
              week,
              rs.getString("status"),
              rs.getLong("row_version"),
              rs.getInt("required_power"),
              chemistry(rs.getObject("couple_id", UUID.class), week),
              week.plusDays(5).atStartOfDay(KST).toInstant(),
              week.plusDays(7).atStartOfDay(KST).toInstant(),
              first.equals(user) ? a : b,
              first.equals(user) ? b : a);
        },
        id);
  }

  private Member member(UUID id, int baseline, boolean joined, Integer snapshot) {
    int current = power(id).total();
    return new Member(id, baseline, current, current - baseline, joined, snapshot);
  }

  private Instant now() {
    return time.now();
  }

  private ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
