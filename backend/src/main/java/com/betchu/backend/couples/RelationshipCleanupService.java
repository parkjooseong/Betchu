package com.betchu.backend.couples;

import com.betchu.backend.common.GameAccess;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RelationshipCleanupService {
  private static final Logger LOG = LoggerFactory.getLogger(RelationshipCleanupService.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final GameAccess access;

  public RelationshipCleanupService(
      JdbcTemplate jdbc, PlatformTransactionManager manager, GameAccess access) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.access = access;
  }

  @Scheduled(
      fixedDelayString = "${betchu.couples.cleanup-delay-ms:5000}",
      initialDelayString = "${betchu.couples.cleanup-delay-ms:5000}")
  public void processPending() {
    List<UUID> jobs =
        jdbc.queryForList(
            "SELECT id FROM relationship_end_jobs WHERE status <> 'COMPLETED' ORDER BY created_at,id LIMIT 50",
            UUID.class);
    for (UUID job : jobs) {
      List<UUID> items =
          jdbc.queryForList(
              "SELECT resource_id FROM relationship_end_job_items WHERE relationship_end_job_id=? AND status='PENDING' ORDER BY resource_id LIMIT 100",
              UUID.class,
              job);
      for (UUID item : items) {
        try {
          processItem(job, item);
        } catch (RuntimeException failure) {
          // Do not log quest text, user identifiers, or SQL payloads.
          LOG.warn(
              "Relationship cleanup will retry a failed item ({})",
              failure.getClass().getSimpleName());
        }
      }
    }
  }

  public void processItem(UUID jobId, UUID questId) {
    transactions.executeWithoutResult(
        transaction -> {
          List<UUID> couples =
              jdbc.queryForList(
                  "SELECT couple_id FROM relationship_end_jobs WHERE id=?", UUID.class, jobId);
          if (couples.isEmpty()) return;
          UUID couple = couples.getFirst();
          access.lockRelationship(couple);
          String state =
              jdbc.queryForObject("SELECT status FROM couples WHERE id=?", String.class, couple);
          if (!"ENDED".equals(state))
            throw new IllegalStateException("Cleanup requires an ended relationship");
          List<String> items =
              jdbc.queryForList(
                  "SELECT status FROM relationship_end_job_items WHERE relationship_end_job_id=? AND resource_type='QUEST' AND resource_id=? FOR UPDATE",
                  String.class,
                  jobId,
                  questId);
          if (items.isEmpty() || "COMPLETED".equals(items.getFirst())) return;
          List<String> statuses =
              jdbc.queryForList(
                  "SELECT status FROM quests WHERE id=? AND couple_id=? FOR UPDATE",
                  String.class,
                  questId,
                  couple);
          if (statuses.isEmpty()) throw new IllegalStateException("Cleanup resource is missing");
          if ("DRAFT".equals(statuses.getFirst())) {
            jdbc.update(
                "UPDATE quests SET status='CANCELED_RELATIONSHIP_ENDED',canceled_reason='RELATIONSHIP_ENDED',row_version=row_version+1 WHERE id=?",
                questId);
          } else if (!"CANCELED_RELATIONSHIP_ENDED".equals(statuses.getFirst())) {
            throw new IllegalStateException("Cleanup resource is not cancelable");
          }
          Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
          jdbc.update(
              "UPDATE relationship_end_job_items SET status='COMPLETED',processed_at=? WHERE relationship_end_job_id=? AND resource_id=? AND resource_type='QUEST'",
              now,
              jobId,
              questId);
          int processed =
              jdbc.queryForObject(
                  "SELECT count(*) FROM relationship_end_job_items WHERE relationship_end_job_id=? AND status='COMPLETED'",
                  Integer.class,
                  jobId);
          jdbc.update(
              """
          UPDATE relationship_end_jobs SET processed_resource_count=?,
            status=CASE WHEN target_resource_count=? THEN 'COMPLETED' ELSE 'PROCESSING' END,
            completed_at=CASE WHEN target_resource_count=? THEN CAST(? AS timestamptz) ELSE NULL END WHERE id=?
          """,
              processed,
              processed,
              processed,
              now,
              jobId);
        });
  }

  @Scheduled(
      fixedDelayString = "${betchu.couples.retention-delay-ms:3600000}",
      initialDelayString = "${betchu.couples.retention-delay-ms:3600000}")
  public void purgeExpiredDraftText() {
    List<UUID> couples =
        jdbc.queryForList(
            """
        SELECT DISTINCT q.couple_id FROM quests q JOIN quest_drafts d ON d.quest_id=q.id
        JOIN couples c ON c.id=q.couple_id
        WHERE q.status IN ('DRAFT','CANCELED_RELATIONSHIP_ENDED') AND c.status='ENDED'
          AND c.ended_at <= clock_timestamp()-interval '30 days'
          AND EXISTS (SELECT 1 FROM relationship_end_job_items i JOIN relationship_end_jobs j ON j.id=i.relationship_end_job_id
            WHERE i.resource_type='QUEST' AND i.resource_id=q.id AND j.couple_id=c.id)
        LIMIT 50
        """,
            UUID.class);
    for (UUID couple : couples) {
      transactions.executeWithoutResult(
          transaction -> {
            access.lockRelationship(couple);
            jdbc.update(
                """
            UPDATE quest_request_receipts r SET response_json=NULL FROM quests q,couples c
            WHERE r.quest_id=q.id AND q.couple_id=c.id AND c.id=? AND r.action='CREATE'
              AND q.status IN ('DRAFT','CANCELED_RELATIONSHIP_ENDED') AND c.status='ENDED'
              AND c.ended_at<=clock_timestamp()-interval '30 days'
              AND EXISTS (SELECT 1 FROM relationship_end_job_items i JOIN relationship_end_jobs j ON j.id=i.relationship_end_job_id
                WHERE i.resource_type='QUEST' AND i.resource_id=q.id AND j.couple_id=c.id)
            """,
                couple);
            jdbc.update(
                """
            DELETE FROM quest_drafts d USING quests q,couples c
            WHERE d.quest_id=q.id AND q.couple_id=c.id AND c.id=?
              AND q.status IN ('DRAFT','CANCELED_RELATIONSHIP_ENDED') AND c.status='ENDED'
              AND c.ended_at<=clock_timestamp()-interval '30 days'
              AND EXISTS (SELECT 1 FROM relationship_end_job_items i JOIN relationship_end_jobs j ON j.id=i.relationship_end_job_id
                WHERE i.resource_type='QUEST' AND i.resource_id=q.id AND j.couple_id=c.id)
            """,
                couple);
          });
    }
  }
}
