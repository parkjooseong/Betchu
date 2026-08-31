package com.betchu.backend;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class BackendPostgresIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

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
