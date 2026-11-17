package com.betchu.backend.notifications;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PushDispatcher {
  private final JdbcTemplate jdbc;
  private final ExpoPushClient client;
  private final TransactionTemplate transactions;
  private final boolean enabled;

  public PushDispatcher(
      JdbcTemplate jdbc,
      ExpoPushClient client,
      PlatformTransactionManager manager,
      @Value("${betchu.notifications.enabled:false}") boolean enabled) {
    this.jdbc = jdbc;
    this.client = client;
    this.transactions = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${betchu.notifications.dispatch-delay-ms:15000}",
      initialDelayString = "${betchu.notifications.dispatch-delay-ms:15000}")
  public void dispatch() {
    if (!enabled) return;
    for (int i = 0; i < 20; i++) {
      UUID id =
          transactions.execute(
              tx -> {
                var rows =
                    jdbc.queryForList(
                        "SELECT id FROM push_deliveries WHERE status IN ('PENDING','ACCEPTED') AND next_attempt_at<=clock_timestamp() ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                        UUID.class);
                if (rows.isEmpty()) return null;
                UUID claim = rows.getFirst();
                jdbc.update(
                    "UPDATE push_deliveries SET next_attempt_at=clock_timestamp()+interval '60 seconds',attempts=attempts+1 WHERE id=?",
                    claim);
                return claim;
              });
      if (id == null) break;
      var rows =
          jdbc.queryForList(
              """
          SELECT p.*,d.token FROM push_deliveries p JOIN push_devices d ON d.id=p.device_id
          JOIN notification_events n ON n.id=p.event_id AND n.user_id=d.user_id
          JOIN users u ON u.id=d.user_id AND u.status='ACTIVE'
          JOIN auth_sessions s ON s.id=d.session_id AND s.status='ACTIVE' AND s.expires_at>clock_timestamp()
          WHERE p.id=? AND n.expires_at>clock_timestamp() AND n.read_at IS NULL
            AND (n.couple_id IS NULL OR EXISTS(SELECT 1 FROM couples c JOIN couple_members m ON m.couple_id=c.id WHERE c.id=n.couple_id AND c.status='CONNECTED' AND m.user_id=n.user_id AND m.status='ACTIVE'))
            AND (n.invite_id IS NULL OR EXISTS(SELECT 1 FROM couple_invites v WHERE v.id=n.invite_id AND v.status='PENDING_CONFIRMATION' AND v.expires_at>clock_timestamp()))
            AND (p.accepted_at IS NULL OR p.accepted_at>clock_timestamp()-interval '24 hours')
          """,
              id);
      if (rows.isEmpty()) {
        jdbc.update("UPDATE push_deliveries SET status='FAILED' WHERE id=?", id);
        continue;
      }
      var row = rows.getFirst();
      boolean receipt = "ACCEPTED".equals(row.get("status"));
      var result =
          receipt
              ? client.receipt((String) row.get("ticket_id"))
              : client.send((String) row.get("token"), (UUID) row.get("event_id"));
      switch (result.status()) {
        case "ACCEPTED" ->
            jdbc.update(
                "UPDATE push_deliveries SET status='ACCEPTED',ticket_id=?,accepted_at=clock_timestamp(),next_attempt_at=clock_timestamp()+interval '15 minutes',attempts=0 WHERE id=?",
                result.ticketId(),
                id);
        case "DEVICE_GONE" ->
            jdbc.update("DELETE FROM push_devices WHERE id=?", row.get("device_id"));
        case "RETRY" -> {
          int attempts = ((Number) row.get("attempts")).intValue();
          if (!receipt && attempts >= 8)
            jdbc.update("UPDATE push_deliveries SET status='FAILED' WHERE id=?", id);
          else
            jdbc.update(
                "UPDATE push_deliveries SET next_attempt_at=clock_timestamp()+?*interval '1 second' WHERE id=?",
                Math.min(3600, 30 * (1 << Math.min(7, attempts))),
                id);
        }
        default ->
            jdbc.update("UPDATE push_deliveries SET status=? WHERE id=?", result.status(), id);
      }
    }
  }
}
