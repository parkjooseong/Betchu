package com.betchu.backend.auth;

import com.betchu.backend.auth.AuthModels.CurrentPolicies;
import com.betchu.backend.auth.AuthModels.Policy;
import com.betchu.backend.common.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public PolicyService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public CurrentPolicies current(String locale) {
    return load(locale, false);
  }

  CurrentPolicies currentLocked() {
    return load("ko-KR", true);
  }

  private CurrentPolicies load(String locale, boolean lock) {
    if (!"ko-KR".equals(locale))
      throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_LOCALE", "현재 한국어 정책만 지원해요.");
    List<Policy> policies =
        jdbc.query(
            "SELECT * FROM policy_versions WHERE locale = ? AND status = 'ACTIVE' AND effective_at <= ? ORDER BY policy_type"
                + (lock ? " FOR SHARE" : ""),
            (rs, row) ->
                new Policy(
                    rs.getObject("id", java.util.UUID.class),
                    rs.getString("policy_type"),
                    rs.getString("policy_version"),
                    rs.getString("locale"),
                    rs.getBoolean("required"),
                    rs.getString("document_url"),
                    rs.getTimestamp("effective_at").toInstant()),
            locale,
            Timestamp.from(clock.instant()));
    boolean ready =
        policies.stream()
                .anyMatch(policy -> policy.required() && "TERMS".equals(policy.policyType()))
            && policies.stream()
                .anyMatch(policy -> policy.required() && "PRIVACY".equals(policy.policyType()));
    return new CurrentPolicies(ready, policies);
  }
}
