package com.betchu.backend.notifications;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  public record Notice(
      UUID id,
      String kind,
      String title,
      String route,
      Instant createdAt,
      Instant expiresAt,
      boolean read) {}

  public record Inbox(List<Notice> notifications, long unreadCount) {}

  public record Preferences(boolean pushEnabled, int registeredDevices) {}

  private final JdbcTemplate jdbc;
  private final GameAccess access;

  public NotificationService(JdbcTemplate jdbc, GameAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @Transactional
  public Inbox inbox(UUID user) {
    access.lockUser(user);
    collect();
    String visible =
        """
        FROM notification_events n WHERE n.user_id=? AND n.expires_at>clock_timestamp()
          AND (n.couple_id IS NULL OR EXISTS(SELECT 1 FROM couples c JOIN couple_members m ON m.couple_id=c.id WHERE c.id=n.couple_id AND c.status='CONNECTED' AND m.user_id=n.user_id AND m.status='ACTIVE'))
          AND (n.invite_id IS NULL OR EXISTS(SELECT 1 FROM couple_invites i WHERE i.id=n.invite_id AND i.status='PENDING_CONFIRMATION' AND i.expires_at>clock_timestamp()))
        """;
    var notices =
        jdbc.query(
            "SELECT n.* "
                + visible
                + " ORDER BY (n.read_at IS NULL) DESC,n.created_at DESC,n.id DESC LIMIT 100",
            (rs, n) ->
                new Notice(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    title(rs.getString("kind")),
                    rs.getString("route"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("read_at") != null),
            user);
    return new Inbox(
        notices,
        jdbc.queryForObject(
            "SELECT count(*) " + visible + " AND n.read_at IS NULL", Long.class, user));
  }

  @Transactional
  public Inbox read(UUID user, List<UUID> ids) {
    access.lockUser(user);
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "알림 목록을 확인해 주세요.");
    for (UUID id : ids)
      jdbc.update(
          "UPDATE notification_events SET read_at=COALESCE(read_at,clock_timestamp()) WHERE id=? AND user_id=?",
          id,
          user);
    return inbox(user);
  }

  @Transactional
  public Preferences register(AuthenticatedUser user, String token, String platform) {
    access.lockUser(user.userId());
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND status='ACTIVE' AND expires_at>clock_timestamp())",
            Boolean.class,
            user.sessionId(),
            user.userId())))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "다시 로그인해 주세요.");
    if (token == null
        || !token.matches("(?:Expo|Exponent)PushToken\\[[A-Za-z0-9_-]{8,160}\\]")
        || !Set.of("ANDROID", "IOS").contains(platform))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE", "알림 기기 정보를 확인해 주세요.");
    // A token belongs to one signed-in installation at a time.
    jdbc.update(
        "DELETE FROM push_devices WHERE token=? AND (user_id<>? OR session_id<>?)",
        token,
        user.userId(),
        user.sessionId());
    jdbc.update(
        "DELETE FROM push_devices WHERE user_id=? AND session_id=? AND token<>?",
        user.userId(),
        user.sessionId(),
        token);
    if (jdbc.queryForObject(
                "SELECT count(*) FROM push_devices WHERE user_id=?", Integer.class, user.userId())
            >= 5
        && jdbc.queryForList("SELECT id FROM push_devices WHERE token=?", token).isEmpty())
      throw new ApiException(HttpStatus.CONFLICT, "DEVICE_LIMIT", "최대 5개 기기에서 알림을 받을 수 있어요.");
    jdbc.update(
        "INSERT INTO push_devices(user_id,session_id,token,platform) VALUES (?,?,?,?) ON CONFLICT(token) DO UPDATE SET updated_at=clock_timestamp()",
        user.userId(),
        user.sessionId(),
        token,
        platform);
    return preferences(user);
  }

  @Transactional
  public Preferences unregister(AuthenticatedUser user) {
    access.lockUser(user.userId());
    jdbc.update(
        "DELETE FROM push_devices WHERE user_id=? AND session_id=?",
        user.userId(),
        user.sessionId());
    return preferences(user);
  }

  @Transactional
  public Preferences preferences(AuthenticatedUser user) {
    access.lockUser(user.userId());
    int total =
        jdbc.queryForObject(
            "SELECT count(*) FROM push_devices WHERE user_id=?", Integer.class, user.userId());
    boolean current =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM push_devices WHERE user_id=? AND session_id=?)",
                Boolean.class,
                user.userId(),
                user.sessionId()));
    return new Preferences(current, total);
  }

  @Scheduled(
      fixedDelayString = "${betchu.notifications.collect-delay-ms:15000}",
      initialDelayString = "${betchu.notifications.collect-delay-ms:15000}")
  @Transactional
  public void collect() {
    jdbc.update(
        """
        INSERT INTO notification_events(user_id,source_key,kind,route,couple_id,expires_at)
        SELECT q.creator_id,'due:'||q.id||':'||v.id,'DUE',CASE WHEN q.quest_type='TUTORIAL' THEN '/tutorial' ELSE '/quest?questId='||q.id END,q.couple_id,v.due_at
        FROM quests q JOIN couples c ON c.id=q.couple_id AND c.status='CONNECTED' JOIN quest_versions v ON v.id=q.approved_quest_version_id
        WHERE q.status='ACTIVE' AND v.due_at BETWEEN clock_timestamp() AND clock_timestamp()+interval '1 hour'
        ON CONFLICT(user_id,source_key) DO NOTHING
        """);
    jdbc.update(
        """
        INSERT INTO notification_events(user_id,source_key,kind,route,invite_id,expires_at)
        SELECT u.id,'invite-deadline:'||i.id,'INVITE_DEADLINE','/',i.id,i.expires_at FROM couple_invites i
        JOIN users u ON (u.id=i.inviter_id AND i.inviter_confirmed_at IS NULL) OR (u.id=i.claimed_by AND i.invitee_confirmed_at IS NULL)
        WHERE i.status='PENDING_CONFIRMATION' AND u.status='ACTIVE' AND i.expires_at BETWEEN clock_timestamp() AND clock_timestamp()+interval '15 minutes'
        ON CONFLICT(user_id,source_key) DO NOTHING
        """);
    jdbc.update(
        """
        INSERT INTO notification_events(user_id,source_key,kind,route,couple_id,expires_at)
        SELECT m.user_id,'boss-deadline:'||b.id,'BOSS_DEADLINE','/coop',b.couple_id,(b.week_start_kst+7)::timestamp AT TIME ZONE 'Asia/Seoul'
        FROM weekly_coop_bosses b JOIN couples c ON c.id=b.couple_id AND c.status='CONNECTED' JOIN couple_members m ON m.couple_id=c.id AND m.status='ACTIVE'
        WHERE b.status IN ('AVAILABLE','WAITING_PARTNER') AND ((m.user_id=b.first_user_id AND b.first_joined_at IS NULL) OR (m.user_id=b.second_user_id AND b.second_joined_at IS NULL))
        AND (b.week_start_kst+7)::timestamp AT TIME ZONE 'Asia/Seoul' BETWEEN clock_timestamp() AND clock_timestamp()+interval '2 hours'
        ON CONFLICT(user_id,source_key) DO NOTHING
        """);
    jdbc.update(
        """
        INSERT INTO notification_events(user_id,source_key,kind,route,couple_id,created_at,expires_at)
        SELECT m.user_id,'quest-event:'||o.id,o.event_type,CASE WHEN q.quest_type='TUTORIAL' THEN '/tutorial' ELSE '/quest?questId='||q.id END,q.couple_id,o.created_at,o.created_at+interval '7 days'
        FROM quest_outbox o JOIN quests q ON q.id=o.quest_id JOIN couples c ON c.id=q.couple_id AND c.status='CONNECTED'
        JOIN couple_members m ON m.couple_id=c.id AND m.status='ACTIVE' JOIN users u ON u.id=m.user_id AND u.status='ACTIVE'
        WHERE (o.recipient_id IS NULL OR o.recipient_id=m.user_id) AND o.created_at>clock_timestamp()-interval '7 days'
        ON CONFLICT(user_id,source_key) DO NOTHING
        """);
    jdbc.update(
        """
        INSERT INTO notification_events(user_id,source_key,kind,route,invite_id,expires_at)
        SELECT u.id,'invite:'||i.id,'INVITE','/',i.id,i.expires_at FROM couple_invites i
        JOIN users u ON (u.id=i.inviter_id OR u.id=i.claimed_by) AND u.status='ACTIVE'
        WHERE i.status='PENDING_CONFIRMATION' AND i.expires_at>clock_timestamp()
        AND ((u.id=i.inviter_id AND i.inviter_confirmed_at IS NULL) OR (u.id=i.claimed_by AND i.invitee_confirmed_at IS NULL))
        ON CONFLICT(user_id,source_key) DO NOTHING
        """);
    jdbc.update(
        """
        INSERT INTO notification_events(user_id,source_key,kind,route,couple_id,expires_at)
        SELECT m.user_id,'deadline:'||q.id||':'||q.current_quest_version_id||':'||q.status,'DEADLINE',CASE WHEN q.quest_type='TUTORIAL' THEN '/tutorial' ELSE '/quest?questId='||q.id END,q.couple_id,
          CASE WHEN q.status='PENDING_APPROVAL' THEN v.approval_deadline_at ELSE v.result_confirmation_deadline_at END
        FROM quests q JOIN couples c ON c.id=q.couple_id AND c.status='CONNECTED'
        JOIN quest_versions v ON v.id=q.current_quest_version_id JOIN couple_members m ON m.couple_id=c.id AND m.status='ACTIVE' AND m.user_id<>q.creator_id
        WHERE (q.status='PENDING_APPROVAL' AND v.approval_deadline_at BETWEEN clock_timestamp() AND clock_timestamp()+interval '1 hour')
           OR (q.status IN ('AWAITING_RESULT','PENDING_FINAL_APPROVAL') AND v.result_confirmation_deadline_at BETWEEN clock_timestamp() AND clock_timestamp()+interval '2 hours')
        ON CONFLICT(user_id,source_key) DO NOTHING
        """);
    jdbc.update(
        "DELETE FROM push_devices d USING auth_sessions s,users u WHERE d.session_id=s.id AND d.user_id=u.id AND (s.status<>'ACTIVE' OR s.expires_at<=clock_timestamp() OR u.status<>'ACTIVE')");
    jdbc.update(
        "DELETE FROM notification_events n WHERE expires_at<=clock_timestamp() OR user_id IN (SELECT id FROM users WHERE status<>'ACTIVE') OR (couple_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM couples c WHERE c.id=n.couple_id AND c.status='CONNECTED')) OR (invite_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM couple_invites i WHERE i.id=n.invite_id AND i.status='PENDING_CONFIRMATION'))");
    jdbc.update(
        """
        INSERT INTO push_deliveries(event_id,device_id)
        SELECT n.id,d.id FROM notification_events n JOIN push_devices d ON d.user_id=n.user_id
        WHERE n.read_at IS NULL AND n.expires_at>clock_timestamp() AND n.created_at>clock_timestamp()-interval '24 hours'
        AND d.updated_at<=n.created_at ON CONFLICT(event_id,device_id) DO NOTHING
        """);
  }

  private String title(String kind) {
    return switch (kind) {
      case "INVITE" -> "커플 연결을 확인해 주세요";
      case "TUTORIAL" -> "첫 약속에서 확인할 내용이 있어요";
      case "BOSS" -> "이번 주 협동 보스 소식이 있어요";
      case "DUE" -> "약속 수행 마감이 얼마 남지 않았어요";
      case "INVITE_DEADLINE" -> "커플 연결 확인 기한이 얼마 남지 않았어요";
      case "BOSS_DEADLINE" -> "이번 주 보스 도전이 곧 끝나요";
      case "DEADLINE" -> "확인 기한이 얼마 남지 않았어요";
      default -> "퀘스트에 새로운 소식이 있어요";
    };
  }
}
