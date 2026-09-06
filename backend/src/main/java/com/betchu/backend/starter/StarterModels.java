package com.betchu.backend.starter;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class StarterModels {

  private StarterModels() {}

  public enum Species {
    STARLIGHT,
    WAVE,
    SUNSET,
    FOREST
  }

  public enum MilestoneType {
    HATCH,
    INTERMEDIATE,
    FINAL,
    MASTERY,
    SUCCESS_80
  }

  public enum Stage {
    EGG
  }

  public record Starter(Species species, String displayName, String description) {}

  public record Stats(int level, int hp, int atk, int power, int exp, int nextLevelRequiredExp) {}

  public record NameRules(int minLength, int maxLength) {}

  public record GrowthMilestone(MilestoneType type, int requiredSuccessCount, String description) {}

  public record Catalogue(
      List<Starter> starters,
      Stats initialStats,
      NameRules nameRules,
      List<GrowthMilestone> growthMilestones) {}

  public record PreviewRequest(Species species, String name) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PreviewRequest fromJson(JsonNode input) {
      if (input == null
          || !input.isObject()
          || input.size() != 2
          || !input.path("species").isString()
          || !input.path("name").isString()) {
        throw new InvalidStarterSelectionException();
      }
      try {
        return new PreviewRequest(
            Species.valueOf(input.path("species").asString()), input.path("name").asString());
      } catch (IllegalArgumentException exception) {
        throw new InvalidStarterSelectionException();
      }
    }
  }

  public record Preview(
      boolean previewOnly,
      Starter starter,
      String name,
      Stage stage,
      int recognizedSuccessCount,
      Stats stats,
      List<GrowthMilestone> growthMilestones) {}

  public record SelectionProblem(
      String type, String title, int status, String detail, String errorCode) {}
}
