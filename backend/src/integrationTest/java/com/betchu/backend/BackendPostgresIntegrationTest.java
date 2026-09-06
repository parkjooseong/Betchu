package com.betchu.backend;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class BackendPostgresIntegrationTest extends PostgresIntegrationTestSupport {

  @Autowired DataSource dataSource;

  @Test
  void startsWithPostgresAndFlyway() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT value FROM app_metadata WHERE key = 'schema_baseline'");
        var result = statement.executeQuery()) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString("value")).isEqualTo("1");
    }
  }
}
