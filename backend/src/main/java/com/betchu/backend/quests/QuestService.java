package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestModels.*;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuestService {
  private final JdbcTemplate jdbc;
  private final GameAccess access;
  private final JsonMapper mapper;
  private static final String VIEW =
      """
      SELECT q.id,q.couple_id,q.status,q.quest_type,q.source_type,q.row_version,q.created_at,
        d.title,d.category,d.success_criteria,d.difficulty,d.stake,d.due_at,d.result_at,
        d.minimum_duration_minutes,d.evidence_method,d.updated_at
      FROM quests q JOIN quest_drafts d ON d.quest_id=q.id
      WHERE q.creator_id=? AND q.couple_id=? AND q.status='DRAFT'
      """;

  public QuestService(JdbcTemplate jdbc, GameAccess access, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.access = access;
    this.mapper = mapper;
  }

  @Transactional
  public DraftView create(UUID user, UUID key, DraftInput input) {
    UUID couple = scope(user, false);
    requireKey(key);
    DraftInput normalized = QuestValidation.normalize(input);
    String hash = hash(mapper.writeValueAsString(normalized));
    Receipt previous = receipt(user, key);
    if (previous != null) {
      checkReceipt(previous, "CREATE", hash);
      ownQuest(user, couple, previous.questId());
      if (previous.response() == null) throw notFound();
      return mapper.readValue(previous.response(), DraftView.class);
    }
    UUID id = UUID.randomUUID();
    Instant now = now();
    jdbc.update(
        "INSERT INTO quests(id,couple_id,creator_id,quest_type,source_type,status,created_at) VALUES (?,?,?,'PERSONAL','CUSTOM','DRAFT',?)",
        id,
        couple,
        user,
        timestamp(now));
    jdbc.update(
        """
        INSERT INTO quest_drafts(quest_id,title,category,success_criteria,difficulty,stake,due_at,
          result_at,minimum_duration_minutes,evidence_method,revision_reason,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,'INITIAL',?)
        """,
        id,
        normalized.title(),
        normalized.category().name(),
        normalized.successCriteria(),
        normalized.difficulty(),
        normalized.stake(),
        timestamp(normalized.dueAt()),
        timestamp(normalized.resultAt()),
        normalized.minimumDurationMinutes(),
        normalized.evidenceMethod(),
        timestamp(now));
    DraftView response = view(user, couple, id);
    saveReceipt(user, key, "CREATE", id, hash, response, now);
    return response;
  }

  @Transactional
  public DraftPage list(UUID user, String status, int limit, String cursor) {
    if (!"DRAFT".equals(status) || limit < 1 || limit > 50) throw QuestValidation.invalid();
    Cursor after = parseCursor(cursor);
    UUID couple = scope(user, false);
    List<DraftView> rows =
        after == null
            ? jdbc.query(
                VIEW + " ORDER BY q.created_at DESC,q.id DESC LIMIT ?",
                QuestService::view,
                user,
                couple,
                limit + 1)
            : jdbc.query(
                VIEW
                    + " AND (q.created_at,q.id) < (?,?) ORDER BY q.created_at DESC,q.id DESC LIMIT ?",
                QuestService::view,
                user,
                couple,
                timestamp(after.createdAt()),
                after.id(),
                limit + 1);
    boolean more = rows.size() > limit;
    List<DraftView> result = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    return new DraftPage(result, more ? cursor(result.getLast()) : null);
  }

  @Transactional
  public DraftView get(UUID user, UUID id) {
    return view(user, scope(user, true), id);
  }

  @Transactional
  public DraftView update(UUID user, UUID id, long expected, DraftInput input) {
    UUID couple = scope(user, true);
    QuestRow row = ownQuest(user, couple, id);
    if (!"DRAFT".equals(row.status())) throw notFound();
    version(row.version(), expected);
    DraftInput normalized = QuestValidation.normalize(input);
    jdbc.update(
        """
        UPDATE quest_drafts SET title=?,category=?,success_criteria=?,difficulty=?,stake=?,due_at=?,
          result_at=?,minimum_duration_minutes=?,evidence_method=?,updated_at=? WHERE quest_id=?
        """,
        normalized.title(),
        normalized.category().name(),
        normalized.successCriteria(),
        normalized.difficulty(),
        normalized.stake(),
        timestamp(normalized.dueAt()),
        timestamp(normalized.resultAt()),
        normalized.minimumDurationMinutes(),
        normalized.evidenceMethod(),
        timestamp(now()),
        id);
    jdbc.update("UPDATE quests SET row_version=row_version+1 WHERE id=?", id);
    return view(user, couple, id);
  }

  @Transactional
  public DiscardResult discard(UUID user, UUID id, long expected, UUID key) {
    UUID couple = scope(user, true);
    requireKey(key);
    if (expected < 0) throw QuestValidation.invalid();
    QuestRow row = ownQuest(user, couple, id);
    String hash = hash(id + ":" + expected);
    Receipt previous = receipt(user, key);
    if (previous != null) {
      checkReceipt(previous, "DISCARD", hash);
      return mapper.readValue(previous.response(), DiscardResult.class);
    }
    if (!"DRAFT".equals(row.status())) throw notFound();
    version(row.version(), expected);
    Instant now = now();
    jdbc.update("DELETE FROM quest_drafts WHERE quest_id=?", id);
    jdbc.update(
        "UPDATE quest_request_receipts SET response_json=NULL WHERE quest_id=? AND action='CREATE'",
        id);
    jdbc.update(
        "UPDATE quests SET status='DISCARDED',discarded_at=?,row_version=row_version+1 WHERE id=?",
        timestamp(now),
        id);
    DiscardResult response = new DiscardResult(id, "DISCARDED", expected + 1);
    saveReceipt(user, key, "DISCARD", id, hash, response, now);
    return response;
  }

  private UUID scope(UUID user, boolean hide) {
    UUID couple = access.lockUser(user);
    if (couple == null) {
      if (hide) throw notFound();
      throw new ApiException(HttpStatus.CONFLICT, "COUPLE_REQUIRED", "커플 연결을 먼저 완료해 주세요.");
    }
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_active_monsters WHERE user_id=?)",
            Boolean.class,
            user)))
      throw new ApiException(HttpStatus.CONFLICT, "STARTER_REQUIRED", "스타팅 배츄를 먼저 선택해 주세요.");
    return couple;
  }

  private QuestRow ownQuest(UUID user, UUID couple, UUID id) {
    return jdbc
        .query(
            "SELECT status,row_version FROM quests WHERE id=? AND creator_id=? AND couple_id=? FOR UPDATE",
            (row, index) -> new QuestRow(row.getString("status"), row.getLong("row_version")),
            id,
            user,
            couple)
        .stream()
        .findFirst()
        .orElseThrow(QuestService::notFound);
  }

  private DraftView view(UUID user, UUID couple, UUID id) {
    return jdbc.query(VIEW + " AND q.id=?", QuestService::view, user, couple, id).stream()
        .findFirst()
        .orElseThrow(QuestService::notFound);
  }

  private Receipt receipt(UUID user, UUID key) {
    return jdbc
        .query(
            "SELECT action,quest_id,request_hash,response_json FROM quest_request_receipts WHERE user_id=? AND idempotency_key=?",
            (row, index) ->
                new Receipt(
                    row.getString("action"),
                    row.getObject("quest_id", UUID.class),
                    row.getString("request_hash"),
                    row.getString("response_json")),
            user,
            key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static void checkReceipt(Receipt receipt, String action, String hash) {
    if (!receipt.action().equals(action) || !receipt.hash().equals(hash))
      throw new ApiException(
          HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 요청에 사용한 키예요. 새 요청으로 다시 시도해 주세요.");
  }

  private void saveReceipt(
      UUID user, UUID key, String action, UUID id, String hash, Object response, Instant now) {
    jdbc.update(
        "INSERT INTO quest_request_receipts(user_id,idempotency_key,action,quest_id,request_hash,response_json,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
        user,
        key,
        action,
        id,
        hash,
        mapper.writeValueAsString(response),
        timestamp(now));
  }

  private static void version(long current, long expected) {
    if (expected < 0) throw QuestValidation.invalid();
    if (current != expected || current == Long.MAX_VALUE)
      throw new ApiException(
          HttpStatus.CONFLICT, "QUEST_VERSION_CONFLICT", "다른 곳에서 초안이 변경됐어요. 다시 불러와 주세요.");
  }

  private static void requireKey(UUID key) {
    if (key == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 요청 키가 필요해요.");
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Cursor parseCursor(String value) {
    if (value == null) return null;
    try {
      if (value.isEmpty() || value.length() > 256) throw new IllegalArgumentException();
      String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\\|", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      Instant created = Instant.parse(parts[0]);
      if (created.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
          || !created.isBefore(Instant.parse("2101-01-01T00:00:00Z")))
        throw new IllegalArgumentException();
      UUID id = UUID.fromString(parts[1]);
      if (!id.toString().equals(parts[1]) || !created.toString().equals(parts[0]))
        throw new IllegalArgumentException();
      return new Cursor(created, id);
    } catch (RuntimeException error) {
      throw QuestValidation.invalid();
    }
  }

  private static String cursor(DraftView view) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((view.createdAt() + "|" + view.id()).getBytes(StandardCharsets.UTF_8));
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  private static Timestamp timestamp(Instant time) {
    return Timestamp.from(time);
  }

  private static DraftView view(ResultSet row, int index) throws SQLException {
    Instant due = row.getTimestamp("due_at").toInstant();
    Instant result = row.getTimestamp("result_at").toInstant();
    int minutes = row.getInt("minimum_duration_minutes");
    return new DraftView(
        row.getObject("id", UUID.class),
        row.getObject("couple_id", UUID.class),
        row.getString("status"),
        row.getString("quest_type"),
        row.getString("source_type"),
        row.getLong("row_version"),
        row.getString("title"),
        Category.valueOf(row.getString("category")),
        row.getString("success_criteria"),
        row.getInt("difficulty"),
        row.getInt("stake"),
        due,
        result,
        minutes,
        row.getString("evidence_method"),
        due.minusSeconds(minutes * 60L),
        result.plusSeconds(86400),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant());
  }

  private record QuestRow(String status, long version) {}

  private record Receipt(String action, UUID questId, String hash, String response) {}

  private record Cursor(Instant createdAt, UUID id) {}

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "QUEST_NOT_FOUND", "초안을 찾을 수 없어요.");
  }
}
