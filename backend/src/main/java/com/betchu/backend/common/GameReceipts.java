package com.betchu.backend.common;

import com.betchu.backend.auth.AuthSecrets;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Called only after the actor's access and transaction lock have been checked. */
@Component
public class GameReceipts {
  private final JdbcTemplate jdbc;
  private final JsonMapper mapper;

  public GameReceipts(JdbcTemplate jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public <T> T execute(
      UUID actor, UUID key, String action, Object input, Class<T> type, Supplier<T> mutation) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Receipts require a transaction");
    if (key == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "요청 키가 필요해요.");
    String hash = AuthSecrets.hash(mapper.writeValueAsString(input));
    var rows =
        jdbc.query(
            "SELECT action,request_hash,response_json::text FROM game_action_receipts WHERE user_id=? AND idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
            actor,
            key);
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      if (!action.equals(row[0]) || !hash.equals(row[1]))
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 요청에 사용한 키예요.");
      return mapper.readValue(row[2], type);
    }
    T result = mutation.get();
    jdbc.update(
        "INSERT INTO game_action_receipts(user_id,idempotency_key,action,request_hash,response_json) VALUES (?,?,?,?,CAST(? AS jsonb))",
        actor,
        key,
        action,
        hash,
        mapper.writeValueAsString(result));
    return result;
  }
}
