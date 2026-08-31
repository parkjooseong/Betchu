package com.betchu.backend.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemControllerTest {

  private final SystemController controller = new SystemController();

  @Test
  void reportsBackendHealth() {
    Map<String, String> response = controller.health();

    assertThat(response).containsEntry("status", "UP").containsEntry("service", "betchu-backend");
  }
}
