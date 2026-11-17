package com.betchu.backend.gameplay;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DrawProbabilityTest {
  @Test
  void everyRandomValueMatchesPublishedBasisPoints() {
    var rarities = List.of("COMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    for (var weights :
        List.of(
            List.of(7500, 2000, 500, 0, 0),
            List.of(0, 5000, 4000, 1000, 0),
            List.of(0, 0, 5000, 4000, 1000))) {
      var counts = new HashMap<String, Integer>();
      for (int roll = 0; roll < 10000; roll++)
        counts.merge(EconomyService.roll(weights, roll), 1, Integer::sum);
      for (int i = 0; i < 5; i++)
        assertThat(counts.getOrDefault(rarities.get(i), 0)).isEqualTo(weights.get(i));
    }
  }

  @Test
  void rejectsInvalidRandomValuesAndUnnormalizedTables() {
    assertThatThrownBy(() -> EconomyService.roll(List.of(7500, 2000, 500, 0, 0), 10000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EconomyService.roll(List.of(7500, 2000, 500, 0, 0), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EconomyService.roll(List.of(75, 20, 5, 0, 0), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
