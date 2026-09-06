package com.betchu.backend.tutorial;

import static com.betchu.backend.tutorial.TutorialModels.*;

import com.betchu.backend.auth.AuthSecrets;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TutorialService {
  private static final Logger LOG = LoggerFactory.getLogger(TutorialService.class);
  private static final Set<String> LIVE =
      Set.of("PENDING_APPROVAL", "ACTIVE", "AWAITING_RESULT", "PENDING_FINAL_APPROVAL");
  private static final Set<String> PARTNER_ACTIONS =
      Set.of("APPROVE", "REJECT", "SELECT_RESULT", "FINAL_APPROVE", "REJECT_RESULT");
  private static final String ROW =
      """
      SELECT q.id,q.couple_id,q.creator_id,q.status,q.row_version,q.current_quest_version_id,
        q.approved_quest_version_id,v.* FROM quests q JOIN quest_versions v ON v.id=q.current_quest_version_id
      WHERE q.id=? AND q.couple_id=? AND q.quest_type='TUTORIAL' FOR UPDATE OF q
      """;
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final TutorialSettlementService settlement;
  private final JsonMapper mapper;
  private final TransactionTemplate transactions;

  public TutorialService(
      JdbcTemplate jdbc,
      GameAccess access,
      TutorialSettlementService settlement,
      JsonMapper mapper,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.settlement = settlement;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public Overview overview(UUID user) {
    return transactions.execute(
        tx -> {
          UUID couple = access.lockUser(user);
          Instant now = now();
          Instant completed =
              jdbc.queryForObject(
                  "SELECT tutorial_completed_at FROM users WHERE id=?",
                  (rs, row) -> instant(rs, "tutorial_completed_at"),
                  user);
          boolean started =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM quests WHERE creator_id=? AND quest_type='TUTORIAL')",
                      Boolean.class,
                      user));
          View own = null, partner = null;
          if (couple != null) {
            List<UUID> ids =
                jdbc.queryForList(
                    "SELECT id FROM quests WHERE couple_id=? AND quest_type='TUTORIAL' ORDER BY id",
                    UUID.class,
                    couple);
            for (UUID id : ids) {
              Row row = lock(id, couple);
              advance(row, now);
              row = lock(id, couple);
              View view = view(row, user, now);
              if (row.creator().equals(user)) own = view;
              else partner = view;
            }
            completed =
                jdbc.queryForObject(
                    "SELECT tutorial_completed_at FROM users WHERE id=?",
                    (rs, row) -> instant(rs, "tutorial_completed_at"),
                    user);
          }
          return new Overview(
              now,
              completed,
              TEMPLATE,
              couple != null && !started && completed == null && hasStarter(user),
              own,
              partner);
        });
  }

  public View get(UUID user, UUID id) {
    return transactions.execute(
        tx -> {
          UUID couple = access.lockUser(user);
          if (couple == null) throw notFound();
          Row row = lock(id, couple);
          Instant now = now();
          advance(row, now);
          return view(lock(id, couple), user, now);
        });
  }

  public View create(UUID user, UUID key, CreateInput input) {
    return transactions.execute(
        tx -> {
          UUID couple = access.lockUser(user);
          requireCouple(couple);
          requireKey(key);
          if (input == null || input.dueAt() == null || input.resultAt() == null) throw invalid();
          Instant due = input.dueAt().truncatedTo(ChronoUnit.MICROS),
              result = input.resultAt().truncatedTo(ChronoUnit.MICROS);
          String hash = hash("CREATE", null, new CreateInput(due, result));
          View replay = replay(user, key, "CREATE", hash, couple, null);
          if (replay != null) return replay;
          boolean exists =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM quests WHERE creator_id=? AND quest_type='TUTORIAL') OR EXISTS(SELECT 1 FROM users WHERE id=? AND tutorial_completed_at IS NOT NULL)",
                      Boolean.class,
                      user,
                      user));
          if (exists) throw conflict("TUTORIAL_ALREADY_EXISTS", "튜토리얼은 계정마다 한 번 진행해요.");
          if (!hasStarter(user)) throw conflict("STARTER_REQUIRED", "함께할 배츄를 먼저 선택해 주세요.");
          Instant now = now(), maximum = now.plus(7, ChronoUnit.DAYS);
          if (!due.isAfter(now)
              || !result.isAfter(due)
              || due.isAfter(maximum)
              || result.isAfter(maximum)) throw invalid();
          UUID id = UUID.randomUUID(), version = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO quests(id,couple_id,creator_id,quest_type,source_type,status,created_at) VALUES (?,?,?,'TUTORIAL','SYSTEM','PENDING_APPROVAL',?)",
              id,
              couple,
              user,
              stamp(now));
          jdbc.update(
              """
          INSERT INTO quest_versions(id,quest_id,version_no,title,category,success_criteria,difficulty,stake,reward,xp,
            minimum_duration_minutes,evidence_method,due_at,result_at,approval_deadline_at,result_confirmation_deadline_at,submitted_by,submitted_at)
          VALUES (?,?,1,?,?,?,1,100,100,10,0,'NONE',?,?,?,?,?,?)
          """,
              version,
              id,
              TEMPLATE.title(),
              TEMPLATE.category(),
              TEMPLATE.successCriteria(),
              stamp(due),
              stamp(result),
              stamp(due),
              stamp(result.plus(1, ChronoUnit.DAYS)),
              user,
              stamp(now));
          jdbc.update("UPDATE quests SET current_quest_version_id=? WHERE id=?", version, id);
          View view = view(lock(id, couple), user, now);
          save(user, key, id, "CREATE", hash, view, now);
          return view;
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
        row -> {
          pendingVersion(row, input.questVersionId());
          if (input.predictedResult() == null) throw invalid();
          settlement.lockStake(id);
          Instant now = now();
          jdbc.update(
              "INSERT INTO quest_approvals(id,quest_id,quest_version_id,partner_id,decision,predicted_result,decided_at) VALUES (?,?,?,?,'APPROVE',?,?)",
              UUID.randomUUID(),
              id,
              row.currentVersion(),
              user,
              input.predictedResult().name(),
              stamp(now));
          jdbc.update(
              "UPDATE quests SET status='ACTIVE',approved_quest_version_id=current_quest_version_id,approved_at=?,row_version=row_version+1 WHERE id=?",
              stamp(now),
              id);
          return null;
        });
  }

  public View reject(UUID user, UUID id, UUID key, RejectInput input) {
    return mutate(
        user,
        id,
        key,
        "REJECT",
        input,
        input.expectedRowVersion(),
        row -> {
          pendingVersion(row, input.questVersionId());
          jdbc.update(
              "INSERT INTO quest_approvals(id,quest_id,quest_version_id,partner_id,decision,decided_at) VALUES (?,?,?,?,'REJECT',?)",
              UUID.randomUUID(),
              id,
              row.currentVersion(),
              user,
              stamp(now()));
          settlement.settle(id, "REJECTED", null);
          return null;
        });
  }

  public View selectResult(UUID user, UUID id, UUID key, ResultInput input) {
    return mutate(
        user,
        id,
        key,
        "SELECT_RESULT",
        input,
        input.expectedRowVersion(),
        row -> {
          resultsOpen(row);
          if (input.selectedResult() == null) throw invalid();
          ResultRow current = resultRow(row);
          if (current.revision() == Integer.MAX_VALUE) throw versionConflict();
          Instant now = now();
          jdbc.update(
              "UPDATE quest_partner_results SET selected_result=?,selection_revision=selection_revision+1,selected_at=? WHERE id=?",
              input.selectedResult().name(),
              stamp(now),
              current.id());
          jdbc.update(
              "INSERT INTO quest_partner_result_events(id,quest_partner_result_id,actor_user_id,event_type,selected_result,idempotency_key,created_at) VALUES (?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              current.id(),
              user,
              current.selected() == null ? "SELECT" : "CHANGE",
              input.selectedResult().name(),
              UUID.nameUUIDFromBytes(
                  ("tutorial-selection:" + user + ":" + key).getBytes(StandardCharsets.UTF_8)),
              stamp(now));
          jdbc.update(
              "UPDATE quests SET status='PENDING_FINAL_APPROVAL',row_version=row_version+1 WHERE id=?",
              id);
          return null;
        });
  }

  public View finalApprove(UUID user, UUID id, UUID key, ResultInput input) {
    return mutate(
        user,
        id,
        key,
        "FINAL_APPROVE",
        input,
        input.expectedRowVersion(),
        row -> {
          if (!"PENDING_FINAL_APPROVAL".equals(row.status())) throw stateConflict();
          ResultRow selected = resultRow(row);
          if (input.selectedResult() == null || selected.selected() != input.selectedResult())
            throw conflict("RESULT_SELECTION_CHANGED", "선택한 결과를 다시 확인해 주세요.");
          settlement.settle(id, input.selectedResult().name(), null);
          return null;
        });
  }

  public View rejectResult(UUID user, UUID id, UUID key, VersionInput input) {
    return mutate(
        user,
        id,
        key,
        "REJECT_RESULT",
        input,
        input.expectedRowVersion(),
        row -> {
          resultsOpen(row);
          String reason =
              resultRow(row).selected() == null
                  ? "PARTNER_JUDGMENT_REJECTED"
                  : "PARTNER_FINAL_APPROVAL_REJECTED";
          settlement.settle(id, "INVALID", reason);
          return null;
        });
  }

  public View cancel(UUID user, UUID id, UUID key, VersionInput input) {
    return mutate(
        user,
        id,
        key,
        "CANCEL",
        input,
        input.expectedRowVersion(),
        row -> {
          cancelOpen(row);
          CancelRequest pending = cancelRequest(id);
          if (pending != null && "PENDING".equals(pending.status()))
            throw conflict("CANCEL_REQUEST_PENDING", "이미 취소 확인을 기다리고 있어요.");
          jdbc.update(
              "INSERT INTO quest_cancel_requests(id,quest_id,requested_by,requested_at,status) VALUES (?,?,?,?,'PENDING')",
              UUID.randomUUID(),
              id,
              user,
              stamp(now()));
          jdbc.update("UPDATE quests SET row_version=row_version+1 WHERE id=?", id);
          return null;
        });
  }

  public View respondCancel(UUID user, UUID id, UUID key, VersionInput input, boolean confirm) {
    return mutate(
        user,
        id,
        key,
        confirm ? "CONFIRM_CANCEL" : "REJECT_CANCEL",
        input,
        input.expectedRowVersion(),
        row -> {
          cancelOpen(row);
          CancelRequest pending = cancelRequest(id);
          if (pending == null || !"PENDING".equals(pending.status())) throw stateConflict();
          if (pending.requestedBy().equals(user)) throw notFound();
          jdbc.update(
              "UPDATE quest_cancel_requests SET status=?,responded_by=?,responded_at=? WHERE id=?",
              confirm ? "CONFIRMED" : "REJECTED",
              user,
              stamp(now()),
              pending.id());
          if (confirm) settlement.settle(id, "CANCELED", null);
          else jdbc.update("UPDATE quests SET row_version=row_version+1 WHERE id=?", id);
          return null;
        });
  }

  private View mutate(
      UUID user,
      UUID id,
      UUID key,
      String action,
      Object payload,
      long expected,
      Function<Row, Void> change) {
    Outcome result =
        transactions.execute(
            tx -> {
              UUID couple = access.lockUser(user);
              if (couple == null) throw notFound();
              requireKey(key);
              Row row = lock(id, couple);
              if (PARTNER_ACTIONS.contains(action) && row.creator().equals(user)) throw notFound();
              String hash = hash(action, id, payload);
              View replay = replay(user, key, action, hash, couple, id);
              if (replay != null) return new Outcome(replay, null);
              Instant now = now();
              String expiry = advance(row, now);
              Row current = lock(id, couple);
              if (expiry != null)
                return new Outcome(null, conflict(expiry, "기한이 지나 튜토리얼 상태가 변경됐어요."));
              if (current.version() != row.version()) return new Outcome(null, versionConflict());
              if (expected < 0) throw invalid();
              if (expected != current.version() || current.version() == Long.MAX_VALUE) {
                throw versionConflict();
              }
              change.apply(current);
              View view = view(lock(id, couple), user, now());
              save(user, key, id, action, hash, view, now());
              return new Outcome(view, null);
            });
    if (result == null) throw new IllegalStateException("Tutorial transaction returned no result");
    if (result.error() != null) throw result.error();
    return result.view();
  }

  private String advance(Row row, Instant now) {
    if (!LIVE.contains(row.status())) return null;
    if ("PENDING_APPROVAL".equals(row.status())
        && !now.isBefore(row.terms().approvalDeadlineAt())) {
      settlement.settle(row.id(), "APPROVAL_EXPIRED", null);
      return "APPROVAL_EXPIRED";
    }
    if (!"PENDING_APPROVAL".equals(row.status())
        && !now.isBefore(row.terms().resultConfirmationDeadlineAt())) {
      settlement.settle(row.id(), "INVALID", "RESULT_CONFIRMATION_TIMEOUT");
      return "RESULT_CONFIRMATION_TIMEOUT";
    }
    if (!now.isBefore(row.terms().dueAt())) {
      int expired =
          jdbc.update(
              "UPDATE quest_cancel_requests SET status='EXPIRED',responded_at=? WHERE quest_id=? AND status='PENDING'",
              stamp(now),
              row.id());
      if (expired > 0)
        jdbc.update("UPDATE quests SET row_version=row_version+1 WHERE id=?", row.id());
    }
    if ("ACTIVE".equals(row.status()) && !now.isBefore(row.terms().resultAt())) {
      resultRow(row);
      jdbc.update(
          "UPDATE quests SET status='AWAITING_RESULT',row_version=row_version+1 WHERE id=?",
          row.id());
    }
    return null;
  }

  public void processDue() {
    List<UUID> ids =
        jdbc.queryForList(
            """
        SELECT q.id FROM quests q JOIN quest_versions v ON v.id=q.current_quest_version_id JOIN couples c ON c.id=q.couple_id
        WHERE q.quest_type='TUTORIAL' AND c.status='CONNECTED' AND
          ((q.status='PENDING_APPROVAL' AND v.approval_deadline_at<=clock_timestamp()) OR
           (q.status='ACTIVE' AND v.result_at<=clock_timestamp()) OR
           (q.status IN ('AWAITING_RESULT','PENDING_FINAL_APPROVAL') AND v.result_confirmation_deadline_at<=clock_timestamp()) OR
           (q.status='ACTIVE' AND v.due_at<=clock_timestamp() AND EXISTS(SELECT 1 FROM quest_cancel_requests r WHERE r.quest_id=q.id AND r.status='PENDING')))
        ORDER BY q.created_at,q.id LIMIT 100
        """,
            UUID.class);
    for (UUID id : ids) {
      try {
        processDue(id);
      } catch (RuntimeException failure) {
        LOG.warn(
            "Tutorial deadline processing will retry a failed item ({})",
            failure.getClass().getSimpleName());
      }
    }
  }

  public void processDue(UUID id) {
    transactions.executeWithoutResult(
        tx -> {
          List<UUID> couples =
              jdbc.queryForList(
                  "SELECT couple_id FROM quests WHERE id=? AND quest_type='TUTORIAL'",
                  UUID.class,
                  id);
          if (couples.isEmpty()) return;
          UUID couple = couples.getFirst();
          access.lockRelationship(couple);
          if (!"CONNECTED"
              .equals(
                  jdbc.queryForObject(
                      "SELECT status FROM couples WHERE id=?", String.class, couple))) return;
          advance(lock(id, couple), now());
        });
  }

  private View view(Row row, UUID user, Instant now) {
    boolean creator = row.creator().equals(user);
    Result predicted = null;
    if (row.approvedVersion() != null) {
      List<String> values =
          jdbc.queryForList(
              "SELECT predicted_result FROM quest_approvals WHERE quest_id=? AND decision='APPROVE'",
              String.class,
              row.id());
      if (!values.isEmpty()) predicted = Result.valueOf(values.getFirst());
    }
    PartnerSelection selection = null;
    if (!creator) {
      List<PartnerSelection> values =
          jdbc.query(
              "SELECT selected_result,selection_revision,selected_at FROM quest_partner_results WHERE quest_id=? AND selected_result IS NOT NULL",
              (rs, index) ->
                  new PartnerSelection(
                      Result.valueOf(rs.getString(1)), rs.getInt(2), instant(rs, "selected_at")),
              row.id());
      if (!values.isEmpty()) selection = values.getFirst();
    }
    CancelRequest cancel = cancelRequest(row.id());
    List<String> actions = new ArrayList<>();
    if (!creator && "PENDING_APPROVAL".equals(row.status()))
      actions.addAll(List.of("APPROVE", "REJECT"));
    if ("ACTIVE".equals(row.status()) && now.isBefore(row.terms().dueAt())) {
      if (cancel == null || !"PENDING".equals(cancel.status())) actions.add("CANCEL");
      else if (!cancel.requestedBy().equals(user))
        actions.addAll(List.of("CONFIRM_CANCEL", "REJECT_CANCEL"));
    }
    if (!creator && Set.of("AWAITING_RESULT", "PENDING_FINAL_APPROVAL").contains(row.status())) {
      actions.add("SELECT_RESULT");
      actions.add("REJECT_RESULT");
      if (selection != null) actions.add("FINAL_APPROVE");
    }
    return new View(
        row.id(),
        row.couple(),
        row.creator(),
        creator ? "CREATOR" : "PARTNER",
        row.status(),
        row.version(),
        row.currentVersion(),
        row.approvedVersion(),
        predicted,
        row.terms(),
        List.copyOf(actions),
        selection,
        cancel,
        readSettlement(row.id()),
        now);
  }

  private Settlement readSettlement(UUID id) {
    List<Settlement> values =
        jdbc.query(
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
                    instant(rs, "settled_at")),
            id);
    return values.isEmpty() ? null : values.getFirst();
  }

  private ResultRow resultRow(Row row) {
    List<ResultRow> rows =
        jdbc.query(
            "SELECT id,selected_result,selection_revision FROM quest_partner_results WHERE quest_id=? FOR UPDATE",
            (rs, index) ->
                new ResultRow(
                    rs.getObject(1, UUID.class),
                    rs.getString(2) == null ? null : Result.valueOf(rs.getString(2)),
                    rs.getInt(3)),
            row.id());
    if (!rows.isEmpty()) return rows.getFirst();
    UUID id = UUID.randomUUID();
    UUID partner =
        jdbc.queryForObject(
            "SELECT user_id FROM couple_members WHERE couple_id=? AND user_id<>?",
            UUID.class,
            row.couple(),
            row.creator());
    jdbc.update(
        "INSERT INTO quest_partner_results(id,quest_id,partner_id,selection_revision) VALUES (?,?,?,0)",
        id,
        row.id(),
        partner);
    return new ResultRow(id, null, 0);
  }

  private CancelRequest cancelRequest(UUID id) {
    List<CancelRequest> rows =
        jdbc.query(
            "SELECT id,requested_by,status,requested_at,responded_at FROM quest_cancel_requests WHERE quest_id=? ORDER BY requested_at DESC,id DESC LIMIT 1",
            (rs, index) ->
                new CancelRequest(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    instant(rs, "requested_at"),
                    instant(rs, "responded_at")),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Row lock(UUID id, UUID couple) {
    return jdbc.query(ROW, TutorialService::row, id, couple).stream()
        .findFirst()
        .orElseThrow(TutorialService::notFound);
  }

  private static Row row(ResultSet rs, int index) throws SQLException {
    Terms terms =
        new Terms(
            rs.getString("title"),
            rs.getString("category"),
            rs.getString("success_criteria"),
            rs.getInt("difficulty"),
            rs.getInt("stake"),
            rs.getInt("reward"),
            rs.getInt("xp"),
            rs.getInt("minimum_duration_minutes"),
            rs.getString("evidence_method"),
            instant(rs, "due_at"),
            instant(rs, "result_at"),
            instant(rs, "approval_deadline_at"),
            instant(rs, "result_confirmation_deadline_at"));
    return new Row(
        rs.getObject(1, UUID.class),
        rs.getObject("couple_id", UUID.class),
        rs.getObject("creator_id", UUID.class),
        rs.getString("status"),
        rs.getLong("row_version"),
        rs.getObject("current_quest_version_id", UUID.class),
        rs.getObject("approved_quest_version_id", UUID.class),
        terms);
  }

  private View replay(
      UUID user, UUID key, String action, String hash, UUID couple, UUID requestedId) {
    List<Receipt> rows =
        jdbc.query(
            "SELECT quest_id,action,request_hash,response_json FROM tutorial_request_receipts WHERE user_id=? AND idempotency_key=?",
            (rs, index) ->
                new Receipt(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            user,
            key);
    if (rows.isEmpty()) return null;
    Receipt receipt = rows.getFirst();
    lock(receipt.quest(), couple);
    if (!receipt.action().equals(action)
        || !receipt.hash().equals(hash)
        || requestedId != null && !requestedId.equals(receipt.quest()))
      throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 요청에 사용한 키예요.");
    if (receipt.response() == null) throw notFound();
    return mapper.readValue(receipt.response(), View.class);
  }

  private void save(
      UUID user, UUID key, UUID id, String action, String hash, View response, Instant now) {
    jdbc.update(
        "INSERT INTO tutorial_request_receipts(user_id,idempotency_key,quest_id,action,request_hash,response_json,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
        user,
        key,
        id,
        action,
        hash,
        mapper.writeValueAsString(response),
        stamp(now));
  }

  private String hash(String action, UUID id, Object value) {
    return AuthSecrets.hash(action + ":" + id + ":" + mapper.writeValueAsString(value));
  }

  private boolean hasStarter(UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_active_monsters WHERE user_id=?)",
            Boolean.class,
            user));
  }

  private void pendingVersion(Row row, UUID version) {
    if (!"PENDING_APPROVAL".equals(row.status())) throw stateConflict();
    if (!row.currentVersion().equals(version)) throw versionConflict();
  }

  private void resultsOpen(Row row) {
    if (!Set.of("AWAITING_RESULT", "PENDING_FINAL_APPROVAL").contains(row.status()))
      throw stateConflict();
  }

  private void cancelOpen(Row row) {
    if (!"ACTIVE".equals(row.status()) || !now().isBefore(row.terms().dueAt()))
      throw conflict("CANCEL_DEADLINE_PASSED", "수행 마감 전에는 두 사람이 동의해 취소할 수 있어요.");
  }

  private static void requireKey(UUID key) {
    if (key == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 요청 키가 필요해요.");
  }

  private static void requireCouple(UUID couple) {
    if (couple == null) throw conflict("COUPLE_REQUIRED", "커플 연결을 먼저 완료해 주세요.");
  }

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "TUTORIAL_NOT_FOUND", "튜토리얼을 찾을 수 없어요.");
  }

  private static ApiException conflict(String code, String detail) {
    return new ApiException(HttpStatus.CONFLICT, code, detail);
  }

  private static ApiException stateConflict() {
    return conflict("TUTORIAL_STATE_CONFLICT", "현재 튜토리얼 상태를 다시 확인해 주세요.");
  }

  private static ApiException versionConflict() {
    return conflict("QUEST_VERSION_CONFLICT", "튜토리얼이 변경됐어요. 다시 불러와 주세요.");
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  private static Timestamp stamp(Instant time) {
    return Timestamp.from(time);
  }

  private static Instant instant(ResultSet rs, String name) throws SQLException {
    Timestamp value = rs.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private record Row(
      UUID id,
      UUID couple,
      UUID creator,
      String status,
      long version,
      UUID currentVersion,
      UUID approvedVersion,
      Terms terms) {}

  private record ResultRow(UUID id, Result selected, int revision) {}

  private record Receipt(UUID quest, String action, String hash, String response) {}

  private record Outcome(View view, ApiException error) {}
}
