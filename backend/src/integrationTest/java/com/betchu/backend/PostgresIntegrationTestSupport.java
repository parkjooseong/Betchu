package com.betchu.backend;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "betchu.couples.cleanup-delay-ms=3600000",
      "betchu.couples.retention-delay-ms=3600000"
    })
@ExtendWith(PostgresIntegrationTestSupport.PostgresAvailable.class)
public abstract class PostgresIntegrationTestSupport {
  private static PostgreSQLContainer container;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    String url = System.getenv("BETCHU_TEST_DB_URL");
    if (url != null && !url.isBlank()) {
      properties.add("spring.datasource.url", () -> url);
      properties.add("spring.datasource.username", () -> System.getenv("BETCHU_TEST_DB_USERNAME"));
      properties.add("spring.datasource.password", () -> System.getenv("BETCHU_TEST_DB_PASSWORD"));
    } else {
      synchronized (PostgresIntegrationTestSupport.class) {
        if (container == null) {
          container = new PostgreSQLContainer("postgres:18.6-alpine");
          container.start();
        }
      }
      properties.add("spring.datasource.url", container::getJdbcUrl);
      properties.add("spring.datasource.username", container::getUsername);
      properties.add("spring.datasource.password", container::getPassword);
    }
  }

  public static class PostgresAvailable implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      String url = System.getenv("BETCHU_TEST_DB_URL");
      return (url != null && !url.isBlank()) || DockerClientFactory.instance().isDockerAvailable()
          ? ConditionEvaluationResult.enabled("PostgreSQL is available")
          : ConditionEvaluationResult.disabled(
              "Neither BETCHU_TEST_DB_URL nor Docker is available");
    }
  }
}
