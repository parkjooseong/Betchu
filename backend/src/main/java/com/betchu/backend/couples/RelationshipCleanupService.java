package com.betchu.backend.couples;

import com.betchu.backend.common.GameAccess;
import com.betchu.backend.tutorial.TutorialSettlementService;
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
  private final TutorialSettlementService tutorials;

  public RelationshipCleanupService(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      GameAccess access,
      TutorialSettlementService tutorials) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.access = access;
    this.tutorials = tutorials;
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
          var statuses =
              jdbc.query(
                  """
              SELECT q.status,q.quest_type,q.stake_locked_at,v.due_at,c.ended_at
              FROM quests q JOIN couples c ON c.id=q.couple_id
              LEFT JOIN quest_versions v ON v.id=COALESCE(q.approved_quest_version_id,q.current_quest_version_id)
              WHERE q.id=? AND q.couple_id=? FOR UPDATE OF q
              """,
                  (row, index) ->
                      new CleanupQuest(
                          row.getString("status"),
                          row.getString("quest_type"),
                          row.getTimestamp("stake_locked_at") != null,
                          row.getTimestamp("due_at"),
                          row.getTimestamp("ended_at")),
                  questId,
                  couple);
          if (statuses.isEmpty()) throw new IllegalStateException("Cleanup resource is missing");
          CleanupQuest quest = statuses.getFirst();
          if ("TUTORIAL".equals(quest.type())) {
            if (quest.dueAt() == null || quest.endedAt() == null)
              throw new IllegalStateException("Tutorial cleanup requires its original dates");
            boolean beforeDue = !quest.locked() || quest.endedAt().before(quest.dueAt());
            tutorials.settle(
                questId,
                beforeDue ? "CANCELED_RELATIONSHIP_ENDED" : "INVALID",
                beforeDue ? null : "RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL");
          } else if ("DRAFT".equals(quest.status())) {
            jdbc.update(
                "UPDATE quests SET status='CANCELED_RELATIONSHIP_ENDED',canceled_reason='RELATIONSHIP_ENDED',row_version=row_version+1 WHERE id=?",
                questId);
          } else if (!"CANCELED_RELATIONSHIP_ENDED".equals(quest.status())) {
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
    purgeExpiredTutorialResponses();
  }

  private void purgeExpiredTutorialResponses() {
    List<UUID> couples =
        jdbc.queryForList(
            """
        SELECT DISTINCT q.couple_id FROM quests q JOIN tutorial_request_receipts r ON r.quest_id=q.id
        JOIN couples c ON c.id=q.couple_id WHERE c.status='ENDED'
          AND c.ended_at<=clock_timestamp()-interval '30 days' AND r.response_json IS NOT NULL LIMIT 50
        """,
            UUID.class);
    for (UUID couple : couples) {
      transactions.executeWithoutResult(
          tx -> {
            access.lockRelationship(couple);
            jdbc.update(
                """
            UPDATE tutorial_request_receipts r SET response_json=NULL FROM quests q,couples c
            WHERE r.quest_id=q.id AND q.couple_id=c.id AND c.id=? AND c.status='ENDED'
              AND c.ended_at<=clock_timestamp()-interval '30 days'
            """,
                couple);
          });
    }
  }

  private record CleanupQuest(
      String status, String type, boolean locked, Timestamp dueAt, Timestamp endedAt) {}
}
