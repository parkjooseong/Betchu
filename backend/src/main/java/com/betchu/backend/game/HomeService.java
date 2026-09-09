package com.betchu.backend.game;

import com.betchu.backend.common.GameAccess;
import com.betchu.backend.game.GameModels.*;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeService {
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final MonsterService monsters;

  public HomeService(JdbcTemplate jdbc, GameAccess access, MonsterService monsters) {
    this.jdbc = jdbc;
    this.access = access;
    this.monsters = monsters;
  }

  @Transactional
  public Home home(UUID userId) {
    UUID coupleId = access.lockUser(userId);
    HomeSelf self =
        jdbc.queryForObject(
            "SELECT u.id,u.nickname,w.available_coins,w.locked_coins FROM users u JOIN wallets w ON w.user_id=u.id WHERE u.id=?",
            (rs, row) ->
                new HomeSelf(
                    userId,
                    rs.getString("nickname"),
                    rs.getLong("available_coins"),
                    rs.getLong("locked_coins"),
                    monsters.activeMonster(userId)),
            userId);
    HomePartner partner = null;
    long draftCount = 0;
    QuestStatusSummary summary = new QuestStatusSummary(0, 0, 0);
    if (coupleId != null) {
      UUID partnerId = monsters.partnerId(coupleId, userId);
      partner =
          jdbc.queryForObject(
              "SELECT id,nickname,profile_image FROM users WHERE id=?",
              (rs, row) ->
                  new HomePartner(
                      partnerId,
                      rs.getString("nickname"),
                      rs.getString("profile_image"),
                      monsters.activeMonster(partnerId)),
              partnerId);
      draftCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM quests q JOIN quest_drafts d ON d.quest_id=q.id WHERE q.couple_id=? AND q.creator_id=? AND q.status IN ('DRAFT','CHANGE_REQUESTED')",
              Long.class,
              coupleId,
              userId);
      summary =
          jdbc.queryForObject(
              """
          SELECT COUNT(*) FILTER (WHERE status='PENDING_APPROVAL'),
                 COUNT(*) FILTER (WHERE status='ACTIVE'),
                 COUNT(*) FILTER (WHERE status IN ('AWAITING_RESULT','PENDING_FINAL_APPROVAL'))
          FROM quests WHERE couple_id=?
          """,
              (rs, row) -> new QuestStatusSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
              coupleId);
    }
    return new Home(
        jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(),
        coupleId,
        self,
        partner,
        draftCount,
        summary);
  }
}
