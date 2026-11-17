package com.betchu.backend.auth;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AppleRevocationService {
  private static final Logger LOG = LoggerFactory.getLogger(AppleRevocationService.class);
  private final JdbcTemplate jdbc;
  private final AppleIdentityClient apple;
  private final TransactionTemplate transactions;

  public AppleRevocationService(
      JdbcTemplate jdbc, AppleIdentityClient apple, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.apple = apple;
    transactions = new TransactionTemplate(manager);
  }

  public void enqueue(String encrypted) {
    if (encrypted != null)
      jdbc.update(
          "INSERT INTO apple_token_revocations(token_hash,encrypted_token) VALUES (?,?) ON CONFLICT DO NOTHING",
          AuthSecrets.hash(encrypted),
          encrypted);
  }

  @Scheduled(
      fixedDelayString = "${betchu.auth.revocation-delay-ms:60000}",
      initialDelayString = "${betchu.auth.revocation-delay-ms:60000}")
  public void process() {
    for (int i = 0; i < 20; i++) {
      var claim =
          transactions.execute(
              tx -> {
                var rows =
                    jdbc.queryForList(
                        "SELECT token_hash,encrypted_token,attempts FROM apple_token_revocations WHERE completed_at IS NULL AND next_attempt_at<=clock_timestamp() ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (rows.isEmpty()) return null;
                var row = rows.getFirst();
                jdbc.update(
                    "UPDATE apple_token_revocations SET next_attempt_at=clock_timestamp()+interval '60 seconds',attempts=attempts+1 WHERE token_hash=?",
                    row.get("token_hash"));
                return row;
              });
      if (claim == null) break;
      try {
        apple.revoke((String) claim.get("encrypted_token"));
        jdbc.update(
            "UPDATE apple_token_revocations SET encrypted_token=NULL,completed_at=clock_timestamp() WHERE token_hash=?",
            claim.get("token_hash"));
      } catch (RuntimeException error) {
        int seconds =
            Math.min(3600, 60 * (1 << Math.min(6, ((Number) claim.get("attempts")).intValue())));
        jdbc.update(
            "UPDATE apple_token_revocations SET next_attempt_at=clock_timestamp()+?*interval '1 second' WHERE token_hash=?",
            seconds,
            claim.get("token_hash"));
        LOG.warn("Apple authorization revocation will retry");
      }
    }
    jdbc.update(
        "DELETE FROM apple_token_revocations WHERE completed_at<clock_timestamp()-interval '30 days'");
  }
}
