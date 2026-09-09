package com.betchu.backend.quests;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class QuestModels {
  private QuestModels() {}

  public enum Category {
    SLEEP,
    EXERCISE,
    STUDY,
    CONTACT,
    GAMING,
    SPENDING,
    HOUSEWORK,
    EATING,
    DATE,
    CUSTOM
  }

  public record DraftInput(
      String title,
      Category category,
      String successCriteria,
      int difficulty,
      int stake,
      Instant dueAt,
      Instant resultAt,
      int minimumDurationMinutes,
      String evidenceMethod) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DraftInput fromJson(JsonNode input) {
      return parse(input, 9);
    }
  }

  public record UpdateInput(long expectedRowVersion, DraftInput draft) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UpdateInput fromJson(JsonNode input) {
      DraftInput draft = parse(input, 10);
      JsonNode version = input.path("expectedRowVersion");
      if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0)
        throw QuestValidation.invalid();
      return new UpdateInput(version.asLong(), draft);
    }
  }

  private static DraftInput parse(JsonNode input, int count) {
    if (input == null || !input.isObject() || input.size() != count)
      throw QuestValidation.invalid();
    for (String name :
        List.of("title", "category", "successCriteria", "dueAt", "resultAt", "evidenceMethod"))
      if (!input.path(name).isString()) throw QuestValidation.invalid();
    for (String name : List.of("difficulty", "stake", "minimumDurationMinutes"))
      if (!input.path(name).isIntegralNumber() || !input.path(name).canConvertToInt())
        throw QuestValidation.invalid();
    try {
      return new DraftInput(
          input.path("title").asString(),
          Category.valueOf(input.path("category").asString()),
          input.path("successCriteria").asString(),
          input.path("difficulty").asInt(),
          input.path("stake").asInt(),
          OffsetDateTime.parse(input.path("dueAt").asString()).toInstant(),
          OffsetDateTime.parse(input.path("resultAt").asString()).toInstant(),
          input.path("minimumDurationMinutes").asInt(),
          input.path("evidenceMethod").asString());
    } catch (RuntimeException error) {
      throw QuestValidation.invalid();
    }
  }

  public record DraftView(
      UUID id,
      UUID coupleId,
      String status,
      String questType,
      String sourceType,
      long rowVersion,
      String title,
      Category category,
      String successCriteria,
      int difficulty,
      int stake,
      Instant dueAt,
      Instant resultAt,
      int minimumDurationMinutes,
      String evidenceMethod,
      Instant approvalDeadlineAt,
      Instant resultConfirmationDeadlineAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record DraftPage(List<DraftView> quests, String nextCursor) {}

  public record DiscardResult(UUID id, String status, long rowVersion) {}
}
