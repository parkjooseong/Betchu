package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestLifecycleModels.*;

import com.betchu.backend.auth.AuthSecrets;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.tutorial.TutorialModels.CancelRequest;
import com.betchu.backend.tutorial.TutorialModels.PartnerSelection;
import com.betchu.backend.tutorial.TutorialModels.Result;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuestLifecycleService {
  private static final Logger LOG = LoggerFactory.getLogger(QuestLifecycleService.class);
  private static final Set<String> EDITABLE = Set.of("DRAFT", "CHANGE_REQUESTED");
  private static final Set<String> LIVE =
      Set.of("PENDING_APPROVAL", "ACTIVE", "AWAITING_RESULT", "PENDING_FINAL_APPROVAL");
  private static final Set<String> PARTNER =
      Set.of(
          "APPROVE", "REQUEST_CHANGE", "REJECT", "SELECT_RESULT", "REJECT_RESULT", "FINAL_APPROVE");
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final QuestService drafts;
  private final QuestSettlementService settlements;
  private final JsonMapper mapper;
  private final TransactionTemplate transactions;

  public QuestLifecycleService(
      JdbcTemplate jdbc,
      GameAccess access,
      QuestService drafts,
      QuestSettlementService settlements,
      JsonMapper mapper,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.drafts = drafts;
    this.settlements = settlements;
    this.mapper = mapper;
    transactions = new TransactionTemplate(manager);
  }

  public Object read(UUID user, UUID id) {
    return transactions.execute(
        tx -> {
          UUID couple = scope(user);
          Row q = visible(user, id, couple);
          advance(q, now());
          q = lock(id, couple);
          return q.creator().equals(user) && EDITABLE.contains(q.status())
              ? drafts.get(user, id)
              : view(q, user, now());
        });
  }

  public View get(UUID user, UUID id) {
    return transactions.execute(
        tx -> {
          UUID couple = scope(user);
          Row q = visible(user, id, couple);
          advance(q, now());
          return view(lock(id, couple), user, now());
        });
  }

  public Page list(UUID user, String status, int limit, String cursor) {
    if (limit < 1
        || limit > 50
        || !Set.of(
                "ALL",
                "PENDING_APPROVAL",
                "CHANGE_REQUESTED",
                "ACTIVE",
                "AWAITING_RESULT",
                "PENDING_FINAL_APPROVAL",
                "SUCCESS",
                "FAILURE",
                "INVALID",
                "CANCELED",
                "REJECTED",
                "APPROVAL_EXPIRED")
            .contains(status)) throw invalid();
    UUID after = null;
    if (cursor != null)
      try {
        after =
            UUID.fromString(
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        if (cursor.length() > 80) throw invalid();
      } catch (IllegalArgumentException error) {
        throw invalid();
      }
    UUID afterId = after;
    return transactions.execute(
        tx -> {
          UUID couple = scope(user);
          advanceCouple(couple);
          List<UUID> ids =
              jdbc.queryForList(
                  """
          SELECT id FROM quests WHERE couple_id=? AND quest_type='PERSONAL'
            AND (creator_id=? OR (current_quest_version_id IS NOT NULL AND status NOT IN ('DRAFT','DISCARDED')))
            AND status<>'DISCARDED' AND (?='ALL' OR status=?) AND (CAST(? AS uuid) IS NULL OR id>CAST(? AS uuid)) ORDER BY id LIMIT ?
          """,
                  UUID.class,
                  couple,
                  user,
                  status,
                  status,
                  afterId,
                  afterId,
                  limit + 1);
          boolean more = ids.size() > limit;
          List<View> views = new ArrayList<>();
          for (UUID id : ids.subList(0, Math.min(limit, ids.size())))
            views.add(view(lock(id, couple), user, now()));
          return new Page(
              List.copyOf(views),
              more
                  ? Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(
                          views.getLast().id().toString().getBytes(StandardCharsets.UTF_8))
                  : null,
              now());
        });
  }

  public Page pending(UUID user) {
    return transactions.execute(
        tx -> {
          UUID couple = scope(user);
          advanceCouple(couple);
          List<View> result = new ArrayList<>();
          for (UUID id :
              jdbc.queryForList(
                  "SELECT id FROM quests WHERE couple_id=? AND quest_type='PERSONAL' AND status IN ('PENDING_APPROVAL','CHANGE_REQUESTED','ACTIVE','AWAITING_RESULT','PENDING_FINAL_APPROVAL') ORDER BY created_at,id",
                  UUID.class,
                  couple)) {
            View value = view(lock(id, couple), user, now());
            if (!value.allowedActions().isEmpty()) result.add(value);
          }
          return new Page(List.copyOf(result), null, now());
        });
  }

  public Versions versions(UUID user, UUID id) {
    return transactions.execute(
        tx -> {
          Row q = visible(user, id, scope(user));
          List<Version> versions = new ArrayList<>();
          for (UUID version :
              jdbc.queryForList(
                  "SELECT id FROM quest_versions WHERE quest_id=? ORDER BY version_no",
                  UUID.class,
                  id)) versions.add(version(version));
          return new Versions(List.copyOf(versions), q.rowVersion());
        });
  }

  public ActivitySummary summary(UUID user) {
    return transactions.execute(
        tx -> {
          UUID couple = scope(user);
          var zone = java.time.ZoneId.of("Asia/Seoul");
          var monday =
              now()
                  .atZone(zone)
                  .toLocalDate()
                  .with(
                      java.time.temporal.TemporalAdjusters.previousOrSame(
                          java.time.DayOfWeek.MONDAY));
          Instant start = monday.atStartOfDay(zone).toInstant(),
              end = monday.plusWeeks(1).atStartOfDay(zone).toInstant();
          long count =
              jdbc.queryForObject(
                  "SELECT COUNT(DISTINCT q.id) FROM quests q JOIN quest_settlements s ON s.quest_id=q.id WHERE q.couple_id=? AND q.quest_type='PERSONAL' AND s.resolved_result IN ('SUCCESS','FAILURE') AND s.settled_at>=? AND s.settled_at<?",
                  Long.class,
                  couple,
                  stamp(start),
                  stamp(end));
          return new ActivitySummary(start, end, count, 10, count >= 10);
        });
  }

  public Quote quote(UUID user, UUID id) {
    return transactions.execute(
        tx -> {
          Row q = visible(user, id, scope(user));
          if (!q.creator().equals(user)) throw notFound();
          if (!EDITABLE.contains(q.status()) && !"PENDING_APPROVAL".equals(q.status()))
            throw state();
          return settlements.quote(user, id);
        });
  }

  public View submit(UUID user, UUID id, UUID key, RowInput input) {
    return mutate(
        user,
        id,
        key,
        "SUBMIT",
        input,
        input.expectedRowVersion(),
        q -> {
          if (!q.creator().equals(user)) throw notFound();
          if (!EDITABLE.contains(q.status())) throw state();
          tutorialComplete(user);
          QuestModels.DraftView d = drafts.get(user, id);
          Terms terms = terms(d);
          if (!now().isBefore(terms.approvalDeadlineAt()))
            throw conflict("APPROVAL_DEADLINE_NOT_FUTURE", "승인 마감이 미래가 되도록 초안을 수정해 주세요.");
          Version previous = q.current() == null ? null : version(q.current());
          if (previous != null && changes(previous.terms(), terms).isEmpty())
            throw conflict("UNCHANGED_QUEST_VERSION", "직전 제출본에서 변경한 내용을 저장한 뒤 다시 보내 주세요.");
          int number = previous == null ? 1 : Math.addExact(previous.versionNo(), 1);
          UUID version = UUID.randomUUID();
          Timestamp now = stamp(now());
          jdbc.update(
              """
          INSERT INTO quest_versions(id,quest_id,version_no,title,category,success_criteria,difficulty,stake,reward,xp,
            minimum_duration_minutes,evidence_method,due_at,result_at,approval_deadline_at,result_confirmation_deadline_at,submitted_by,submitted_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
              version,
              id,
              number,
              terms.title(),
              terms.category().name(),
              terms.successCriteria(),
              terms.difficulty(),
              terms.stake(),
              terms.reward(),
              terms.xp(),
              terms.minimumDurationMinutes(),
              terms.evidenceMethod(),
              stamp(terms.dueAt()),
              stamp(terms.resultAt()),
              stamp(terms.approvalDeadlineAt()),
              stamp(terms.resultConfirmationDeadlineAt()),
              user,
              now);
          jdbc.update(
              "UPDATE quests SET status='PENDING_APPROVAL',current_quest_version_id=?,row_version=row_version+1 WHERE id=?",
              version,
              id);
        });
  }

  public View recall(UUID user, UUID id, UUID key, VersionInput input) {
    return mutate(
        user,
        id,
        key,
        "RECALL",
        input,
        input.expectedRowVersion(),
        q -> {
          if (!q.creator().equals(user)) throw notFound();
          pendingVersion(q, input.questVersionId());
          jdbc.update(
              "INSERT INTO quest_recall_events(id,quest_id,quest_version_id,requested_by,idempotency_key,recalled_at) VALUES (?,?,?,?,?,?)",
              UUID.randomUUID(),
              id,
              q.current(),
              user,
              key,
              stamp(now()));
          jdbc.update(
              "UPDATE quest_drafts SET revision_reason='CREATOR_RECALL' WHERE quest_id=?", id);
          transition(id, "DRAFT");
        });
  }

  public View requestChange(UUID user, UUID id, UUID key, ChangeInput input) {
    String message = QuestValidation.message(input.requestMessage());
    ChangeInput normalized =
        new ChangeInput(input.questVersionId(), input.expectedRowVersion(), message);
    return mutate(
        user,
        id,
        key,
        "REQUEST_CHANGE",
        normalized,
        input.expectedRowVersion(),
        q -> {
          pendingVersion(q, input.questVersionId());
          decision(q, user, "REQUEST_CHANGE", null, message);
          jdbc.update(
              "UPDATE quest_drafts SET revision_reason='PARTNER_CHANGE_REQUEST' WHERE quest_id=?",
              id);
          transition(id, "CHANGE_REQUESTED");
        });
  }

  public View reject(UUID user, UUID id, UUID key, VersionInput input) {
    return mutate(
        user,
        id,
        key,
        "REJECT",
        input,
        input.expectedRowVersion(),
        q -> {
          pendingVersion(q, input.questVersionId());
          decision(q, user, "REJECT", null, null);
          settlements.settle(id, "REJECTED", null);
        });
  }

  public View approve(UUID user, UUID id, UUID key, ApproveInput input) {
    return mutate(
        user,
        id,
        key,
        "APPROVE",
        input,
        input.expectedRowVersion(),
        q -> {
          pendingVersion(q, input.questVersionId());
          tutorialComplete(q.creator());
          if (input.predictedResult() == null) throw invalid();
          settlements.reserve(id);
          decision(q, user, "APPROVE", input.predictedResult(), null);
          jdbc.update(
              "UPDATE quests SET status='ACTIVE',approved_quest_version_id=current_quest_version_id,approved_at=?,row_version=row_version+1 WHERE id=?",
              stamp(now()),
              id);
        });
  }

  public View select(UUID user, UUID id, UUID key, ResultInput input) {
    return mutate(
        user,
        id,
        key,
        "SELECT_RESULT",
        input,
        input.expectedRowVersion(),
        q -> {
          resultsOpen(q);
          if (input.selectedResult() == null) throw invalid();
          ResultRow result = result(q);
          if (result.revision() == Integer.MAX_VALUE) throw state();
          Timestamp now = stamp(now());
          jdbc.update(
              "UPDATE quest_partner_results SET selected_result=?,selection_revision=selection_revision+1,selected_at=? WHERE id=?",
              input.selectedResult().name(),
              now,
              result.id());
          jdbc.update(
              "INSERT INTO quest_partner_result_events(id,quest_partner_result_id,actor_user_id,event_type,selected_result,idempotency_key,created_at) VALUES (?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              result.id(),
              user,
              result.selected() == null ? "SELECT" : "CHANGE",
              input.selectedResult().name(),
              UUID.nameUUIDFromBytes(
                  ("personal-selection:" + user + ":" + key).getBytes(StandardCharsets.UTF_8)),
              now);
          transition(id, "PENDING_FINAL_APPROVAL");
        });
  }

  public View finalApprove(UUID user, UUID id, UUID key, FinalInput input) {
    return mutate(
        user,
        id,
        key,
        "FINAL_APPROVE",
        input,
        input.expectedRowVersion(),
        q -> {
          if (!"PENDING_FINAL_APPROVAL".equals(q.status())) throw state();
          ResultRow selected = result(q);
          if (selected.selected() != input.selectedResult()
              || selected.revision() != input.selectionRevision())
            throw conflict("RESULT_SELECTION_CHANGED", "현재 저장된 결과와 선택 버전을 다시 확인해 주세요.");
          settlements.settle(id, input.selectedResult().name(), null);
        });
  }

  public View rejectResult(UUID user, UUID id, UUID key, RowInput input) {
    return mutate(
        user,
        id,
        key,
        "REJECT_RESULT",
        input,
        input.expectedRowVersion(),
        q -> {
          resultsOpen(q);
          settlements.settle(
              id,
              "INVALID",
              "AWAITING_RESULT".equals(q.status())
                  ? "PARTNER_JUDGMENT_REJECTED"
                  : "PARTNER_FINAL_APPROVAL_REJECTED");
        });
  }

  public View cancel(UUID user, UUID id, UUID key, RowInput input) {
    return mutate(
        user,
        id,
        key,
        "CANCEL",
        input,
        input.expectedRowVersion(),
        q -> {
          cancelOpen(q);
          CancelRequest request = cancelRequest(id);
          if (request != null && "PENDING".equals(request.status())) throw state();
          jdbc.update(
              "INSERT INTO quest_cancel_requests(id,quest_id,requested_by,requested_at,status) VALUES (?,?,?,?,'PENDING')",
              UUID.randomUUID(),
              id,
              user,
              stamp(now()));
          transition(id, "ACTIVE");
        });
  }

  public View respondCancel(UUID user, UUID id, UUID key, RowInput input, boolean confirm) {
    return mutate(
        user,
        id,
        key,
        confirm ? "CONFIRM_CANCEL" : "REJECT_CANCEL",
        input,
        input.expectedRowVersion(),
        q -> {
          cancelOpen(q);
          CancelRequest request = cancelRequest(id);
          if (request == null || !"PENDING".equals(request.status())) throw state();
          if (request.requestedBy().equals(user)) throw notFound();
          jdbc.update(
              "UPDATE quest_cancel_requests SET status=?,responded_by=?,responded_at=? WHERE id=?",
              confirm ? "CONFIRMED" : "REJECTED",
              user,
              stamp(now()),
              request.id());
          if (confirm) settlements.settle(id, "CANCELED", null);
          else transition(id, "ACTIVE");
        });
  }

  private View mutate(
      UUID user,
      UUID id,
      UUID key,
      String action,
      Object input,
      long expected,
      Consumer<Row> change) {
    Outcome outcome =
        transactions.execute(
            tx -> {
              UUID couple = scope(user);
              Row original = visible(user, id, couple);
              if (key == null || input == null || expected < 0) throw invalid();
              if (PARTNER.contains(action) && original.creator().equals(user)) throw notFound();
              String hash =
                  AuthSecrets.hash(action + ":" + id + ":" + mapper.writeValueAsString(input));
              View replay = replay(user, id, key, action, hash);
              if (replay != null) return new Outcome(replay, null);
              String expiry = advance(original, now());
              Row q = lock(id, couple);
              if (expiry != null)
                return new Outcome(null, conflict(expiry, "기한에 따라 상태가 바뀌었어요. 다시 확인해 주세요."));
              if (original.rowVersion() != q.rowVersion())
                return new Outcome(null, versionConflict());
              if (q.rowVersion() != expected || expected == Long.MAX_VALUE) throw versionConflict();
              change.accept(q);
              Row updated = lock(id, couple);
              View response = view(updated, user, now());
              jdbc.update(
                  "INSERT INTO quest_request_receipts(user_id,idempotency_key,action,quest_id,request_hash,response_json,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
                  user,
                  key,
                  action,
                  id,
                  hash,
                  mapper.writeValueAsString(response),
                  stamp(now()));
              event(updated, action, "SELECT_RESULT".equals(action) ? user : null);
              return new Outcome(response, null);
            });
    if (outcome.error() != null) throw outcome.error();
    return outcome.view();
  }

  private View view(Row q, UUID user, Instant now) {
    boolean creator = q.creator().equals(user);
    Version version =
        q.current() == null ? null : version(q.approved() == null ? q.current() : q.approved());
    QuestModels.DraftView draft =
        creator && EDITABLE.contains(q.status()) ? drafts.get(user, q.id()) : null;
    Decision decision = null;
    Result prediction = null;
    if (q.current() != null) {
      List<Decision> decisions =
          jdbc.query(
              "SELECT decision,request_message,decided_at FROM quest_approvals WHERE quest_id=? AND quest_version_id=?",
              (rs, n) ->
                  new Decision(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
              q.id(),
              q.current());
      if (!decisions.isEmpty()) decision = decisions.getFirst();
      if (q.approved() != null)
        prediction =
            Result.valueOf(
                jdbc.queryForObject(
                    "SELECT predicted_result FROM quest_approvals WHERE quest_id=? AND quest_version_id=? AND decision='APPROVE'",
                    String.class,
                    q.id(),
                    q.approved()));
    }
    PartnerSelection selection = null;
    if (!creator)
      selection =
          jdbc
              .query(
                  "SELECT selected_result,selection_revision,selected_at FROM quest_partner_results WHERE quest_id=? AND selected_result IS NOT NULL",
                  (rs, n) ->
                      new PartnerSelection(
                          Result.valueOf(rs.getString(1)),
                          rs.getInt(2),
                          rs.getTimestamp(3).toInstant()),
                  q.id())
              .stream()
              .findFirst()
              .orElse(null);
    CancelRequest cancel = cancelRequest(q.id());
    List<String> actions = new ArrayList<>();
    if (creator && EDITABLE.contains(q.status()))
      actions.addAll(List.of("EDIT", "SUBMIT", "DISCARD"));
    if ("PENDING_APPROVAL".equals(q.status()))
      actions.addAll(creator ? List.of("RECALL") : List.of("APPROVE", "REQUEST_CHANGE", "REJECT"));
    if ("ACTIVE".equals(q.status()) && now.isBefore(version.terms().dueAt())) {
      if (cancel == null || !"PENDING".equals(cancel.status())) actions.add("CANCEL");
      else if (!cancel.requestedBy().equals(user))
        actions.addAll(List.of("CONFIRM_CANCEL", "REJECT_CANCEL"));
    }
    if (!creator && Set.of("AWAITING_RESULT", "PENDING_FINAL_APPROVAL").contains(q.status())) {
      actions.addAll(List.of("SELECT_RESULT", "REJECT_RESULT"));
      if (selection != null) actions.add("FINAL_APPROVE");
    }
    return new View(
        q.id(),
        q.couple(),
        q.creator(),
        creator ? "CREATOR" : "PARTNER",
        q.status(),
        !creator && "DRAFT".equals(q.status()) ? "RECALLED" : null,
        q.rowVersion(),
        q.current(),
        q.approved(),
        version,
        draft,
        prediction,
        q.date(),
        q.approved() == null ? null : version.terms().stake(),
        q.reward(),
        decision,
        selection,
        cancel,
        settlements.settlement(q.id()),
        List.copyOf(actions),
        now);
  }

  private Version version(UUID id) {
    Version version =
        jdbc.queryForObject(
            "SELECT * FROM quest_versions WHERE id=?",
            (rs, n) ->
                new Version(
                    id,
                    rs.getInt("version_no"),
                    terms(rs),
                    List.of(),
                    rs.getTimestamp("submitted_at").toInstant()),
            id);
    List<Terms> previous =
        jdbc.query(
            "SELECT v.* FROM quest_versions v JOIN quest_versions current ON current.quest_id=v.quest_id WHERE current.id=? AND v.version_no=current.version_no-1",
            (rs, n) -> terms(rs),
            id);
    return new Version(
        id,
        version.versionNo(),
        version.terms(),
        previous.isEmpty() ? List.of() : changes(previous.getFirst(), version.terms()),
        version.submittedAt());
  }

  private String advance(Row q, Instant now) {
    if (!LIVE.contains(q.status())) return null;
    Terms terms = version(q.current()).terms();
    if ("PENDING_APPROVAL".equals(q.status()) && !now.isBefore(terms.approvalDeadlineAt())) {
      settlements.settle(q.id(), "APPROVAL_EXPIRED", null);
      return "APPROVAL_EXPIRED";
    }
    if (!"PENDING_APPROVAL".equals(q.status())
        && !now.isBefore(terms.resultConfirmationDeadlineAt())) {
      settlements.settle(q.id(), "INVALID", "RESULT_CONFIRMATION_TIMEOUT");
      return "RESULT_CONFIRMATION_TIMEOUT";
    }
    boolean changed = false;
    if (!now.isBefore(terms.dueAt()))
      changed =
          jdbc.update(
                  "UPDATE quest_cancel_requests SET status='EXPIRED',responded_at=? WHERE quest_id=? AND status='PENDING'",
                  stamp(now),
                  q.id())
              > 0;
    String status = q.status();
    if ("ACTIVE".equals(status) && !now.isBefore(terms.resultAt())) {
      result(q);
      status = "AWAITING_RESULT";
      changed = true;
    }
    if (changed) {
      transition(q.id(), status);
      event(lock(q.id(), q.couple()), "TIME_ADVANCED", null);
    }
    return null;
  }

  public void processDue() {
    List<UUID> ids =
        jdbc.queryForList(
            """
        SELECT q.id FROM quests q JOIN quest_versions v ON v.id=q.current_quest_version_id JOIN couples c ON c.id=q.couple_id
        WHERE q.quest_type='PERSONAL' AND c.status='CONNECTED' AND
         ((q.status='PENDING_APPROVAL' AND v.approval_deadline_at<=clock_timestamp()) OR
          (q.status='ACTIVE' AND v.result_at<=clock_timestamp()) OR
          (q.status IN ('AWAITING_RESULT','PENDING_FINAL_APPROVAL') AND v.result_confirmation_deadline_at<=clock_timestamp()) OR
          (q.status='ACTIVE' AND v.due_at<=clock_timestamp() AND EXISTS(SELECT 1 FROM quest_cancel_requests r WHERE r.quest_id=q.id AND r.status='PENDING')))
        ORDER BY q.created_at,q.id LIMIT 100
        """,
            UUID.class);
    for (UUID id : ids)
      try {
        processDue(id);
      } catch (RuntimeException failure) {
        LOG.warn("Personal quest deadline will retry ({})", failure.getClass().getSimpleName());
      }
  }

  public void processDue(UUID id) {
    transactions.executeWithoutResult(
        tx -> {
          List<UUID> couples =
              jdbc.queryForList(
                  "SELECT couple_id FROM quests WHERE id=? AND quest_type='PERSONAL'",
                  UUID.class,
                  id);
          if (couples.isEmpty()) return;
          UUID couple = couples.getFirst();
          access.lockRelationship(couple);
          if ("CONNECTED"
              .equals(
                  jdbc.queryForObject(
                      "SELECT status FROM couples WHERE id=?", String.class, couple)))
            advance(lock(id, couple), now());
        });
  }

  private void advanceCouple(UUID couple) {
    for (UUID id :
        jdbc.queryForList(
            "SELECT id FROM quests WHERE couple_id=? AND quest_type='PERSONAL' AND status IN ('PENDING_APPROVAL','ACTIVE','AWAITING_RESULT','PENDING_FINAL_APPROVAL') ORDER BY id",
            UUID.class,
            couple)) advance(lock(id, couple), now());
  }

  private Row visible(UUID user, UUID id, UUID couple) {
    Row q = lock(id, couple);
    if (!q.creator().equals(user) && q.current() == null) throw notFound();
    if ("DISCARDED".equals(q.status()) && q.current() == null) throw notFound();
    return q;
  }

  private Row lock(UUID id, UUID couple) {
    return jdbc
        .query(
            "SELECT id,couple_id,creator_id,status,row_version,current_quest_version_id,approved_quest_version_id,budget_date_kst,reward_reserved_amount FROM quests WHERE id=? AND couple_id=? AND quest_type='PERSONAL' FOR UPDATE",
            (rs, n) ->
                new Row(
                    id,
                    couple,
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getLong(5),
                    rs.getObject(6, UUID.class),
                    rs.getObject(7, UUID.class),
                    rs.getObject(8, LocalDate.class),
                    rs.getObject(9, Integer.class)),
            id,
            couple)
        .stream()
        .findFirst()
        .orElseThrow(QuestLifecycleModels::notFound);
  }

  private UUID scope(UUID user) {
    UUID couple = access.lockUser(user);
    if (couple == null) throw notFound();
    return couple;
  }

  private void tutorialComplete(UUID user) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT tutorial_completed_at IS NOT NULL FROM users WHERE id=?", Boolean.class, user)))
      throw conflict("TUTORIAL_REQUIRED", "첫 약속을 마친 뒤 일반 퀘스트를 보낼 수 있어요.");
  }

  private void pendingVersion(Row q, UUID version) {
    if (!"PENDING_APPROVAL".equals(q.status())) throw state();
    if (!q.current().equals(version)) throw versionConflict();
  }

  private void resultsOpen(Row q) {
    if (!Set.of("AWAITING_RESULT", "PENDING_FINAL_APPROVAL").contains(q.status())) throw state();
  }

  private void cancelOpen(Row q) {
    if (!"ACTIVE".equals(q.status()) || !now().isBefore(version(q.current()).terms().dueAt()))
      throw state();
  }

  private void transition(UUID id, String status) {
    jdbc.update("UPDATE quests SET status=?,row_version=row_version+1 WHERE id=?", status, id);
  }

  private void decision(Row q, UUID user, String decision, Result prediction, String message) {
    jdbc.update(
        "INSERT INTO quest_approvals(id,quest_id,quest_version_id,partner_id,decision,predicted_result,request_message,decided_at) VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        q.id(),
        q.current(),
        user,
        decision,
        prediction == null ? null : prediction.name(),
        message,
        stamp(now()));
  }

  private ResultRow result(Row q) {
    jdbc.update(
        "INSERT INTO quest_partner_results(id,quest_id,partner_id) SELECT ?,?,user_id FROM couple_members WHERE couple_id=? AND user_id<>? ON CONFLICT(quest_id) DO NOTHING",
        UUID.randomUUID(),
        q.id(),
        q.couple(),
        q.creator());
    return jdbc.queryForObject(
        "SELECT id,selected_result,selection_revision FROM quest_partner_results WHERE quest_id=? FOR UPDATE",
        (rs, n) ->
            new ResultRow(
                rs.getObject(1, UUID.class),
                rs.getString(2) == null ? null : Result.valueOf(rs.getString(2)),
                rs.getInt(3)),
        q.id());
  }

  private CancelRequest cancelRequest(UUID id) {
    return jdbc
        .query(
            "SELECT id,requested_by,status,requested_at,responded_at FROM quest_cancel_requests WHERE quest_id=? ORDER BY requested_at DESC,id DESC LIMIT 1",
            (rs, n) ->
                new CancelRequest(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getTimestamp(4).toInstant(),
                    rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private View replay(UUID user, UUID id, UUID key, String action, String hash) {
    List<Receipt> rows =
        jdbc.query(
            "SELECT quest_id,action,request_hash,response_json FROM quest_request_receipts WHERE user_id=? AND idempotency_key=?",
            (rs, n) ->
                new Receipt(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            user,
            key);
    if (rows.isEmpty()) return null;
    Receipt r = rows.getFirst();
    if (!id.equals(r.id()) || !action.equals(r.action()) || !hash.equals(r.hash()))
      throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 요청에 사용한 키예요.");
    if (r.response() == null) throw notFound();
    return mapper.readValue(r.response(), View.class);
  }

  private void event(Row q, String type, UUID recipient) {
    jdbc.update(
        "INSERT INTO quest_outbox(id,quest_id,event_type,row_version,recipient_id,created_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID(),
        q.id(),
        type,
        q.rowVersion(),
        recipient,
        stamp(now()));
  }

  private static Terms terms(QuestModels.DraftView d) {
    return new Terms(
        d.title(),
        d.category(),
        d.successCriteria(),
        d.difficulty(),
        d.stake(),
        d.stake() / 2,
        new int[] {0, 10, 20, 40, 70}[d.difficulty()],
        d.minimumDurationMinutes(),
        d.evidenceMethod(),
        d.dueAt(),
        d.resultAt(),
        d.approvalDeadlineAt(),
        d.resultConfirmationDeadlineAt());
  }

  private static Terms terms(ResultSet rs) throws SQLException {
    return new Terms(
        rs.getString("title"),
        QuestModels.Category.valueOf(rs.getString("category")),
        rs.getString("success_criteria"),
        rs.getInt("difficulty"),
        rs.getInt("stake"),
        rs.getInt("reward"),
        rs.getInt("xp"),
        rs.getInt("minimum_duration_minutes"),
        rs.getString("evidence_method"),
        rs.getTimestamp("due_at").toInstant(),
        rs.getTimestamp("result_at").toInstant(),
        rs.getTimestamp("approval_deadline_at").toInstant(),
        rs.getTimestamp("result_confirmation_deadline_at").toInstant());
  }

  private static List<String> changes(Terms a, Terms b) {
    String[] fields = {
      "title",
      "category",
      "successCriteria",
      "difficulty",
      "stake",
      "minimumDurationMinutes",
      "evidenceMethod",
      "dueAt",
      "resultAt"
    };
    Object[]
        before =
            {
              a.title(),
              a.category(),
              a.successCriteria(),
              a.difficulty(),
              a.stake(),
              a.minimumDurationMinutes(),
              a.evidenceMethod(),
              a.dueAt(),
              a.resultAt()
            },
        after =
            {
              b.title(),
              b.category(),
              b.successCriteria(),
              b.difficulty(),
              b.stake(),
              b.minimumDurationMinutes(),
              b.evidenceMethod(),
              b.dueAt(),
              b.resultAt()
            };
    List<String> changed = new ArrayList<>();
    for (int i = 0; i < fields.length; i++)
      if (!Objects.equals(before[i], after[i])) changed.add(fields[i]);
    return List.copyOf(changed);
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  private static Timestamp stamp(Instant time) {
    return Timestamp.from(time);
  }

  private static ApiException versionConflict() {
    return conflict("QUEST_VERSION_CONFLICT", "다른 요청이나 기한 처리로 퀘스트가 바뀌었어요. 다시 확인해 주세요.");
  }

  private static ApiException state() {
    return conflict("QUEST_STATE_CONFLICT", "현재 상태에서는 처리할 수 없어요. 다시 확인해 주세요.");
  }

  private record Row(
      UUID id,
      UUID couple,
      UUID creator,
      String status,
      long rowVersion,
      UUID current,
      UUID approved,
      LocalDate date,
      Integer reward) {}

  private record Receipt(UUID id, String action, String hash, String response) {}

  private record ResultRow(UUID id, Result selected, int revision) {}

  private record Outcome(View view, ApiException error) {}
}
