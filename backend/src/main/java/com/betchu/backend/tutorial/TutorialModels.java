package com.betchu.backend.tutorial;

import com.betchu.backend.common.ApiException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

public final class TutorialModels {
  private TutorialModels() {}

  public enum Result {
    SUCCESS,
    FAILURE
  }

  public record Template(
      String title,
      String category,
      String successCriteria,
      int difficulty,
      int stake,
      int reward,
      int xp,
      int minimumDurationMinutes,
      String evidenceMethod) {}

  public static final Template TEMPLATE =
      new Template(
          "자기 전에 물 한 잔 더 마시기", "EATING", "자기 전에 물 한 잔을 더 마셔요.", 1, 100, 100, 10, 0, "NONE");

  public record Terms(
      String title,
      String category,
      String successCriteria,
      int difficulty,
      int stake,
      int reward,
      int xp,
      int minimumDurationMinutes,
      String evidenceMethod,
      Instant dueAt,
      Instant resultAt,
      Instant approvalDeadlineAt,
      Instant resultConfirmationDeadlineAt) {}

  public record PartnerSelection(
      Result selectedResult, int selectionRevision, Instant selectedAt) {}

  public record CancelRequest(
      UUID id, UUID requestedBy, String status, Instant requestedAt, Instant respondedAt) {}

  public record Settlement(
      UUID id,
      String resolvedResult,
      String invalidReason,
      int stake,
      int reward,
      int xp,
      UUID creditedMonsterId,
      Integer recognizedSuccessBefore,
      Integer recognizedSuccessAfter,
      String growthStageBefore,
      String growthStageAfter,
      Instant settledAt) {}

  public record View(
      UUID id,
      UUID coupleId,
      UUID creatorId,
      String viewerRole,
      String status,
      long rowVersion,
      UUID questVersionId,
      UUID approvedQuestVersionId,
      Result predictedResult,
      Terms terms,
      List<String> allowedActions,
      @JsonInclude(JsonInclude.Include.NON_NULL) PartnerSelection partnerSelection,
      CancelRequest cancelRequest,
      Settlement settlement,
      Instant serverTime) {}

  public record Overview(
      Instant serverTime,
      Instant completedAt,
      Template template,
      boolean canStart,
      View own,
      View partner) {}

  public record CreateInput(Instant dueAt, Instant resultAt) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CreateInput parse(JsonNode node) {
      object(node, 2);
      return new CreateInput(time(node, "dueAt"), time(node, "resultAt"));
    }
  }

  public record ApproveInput(UUID questVersionId, long expectedRowVersion, Result predictedResult) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ApproveInput parse(JsonNode node) {
      object(node, 3);
      return new ApproveInput(
          uuid(node, "questVersionId"), version(node), result(node, "predictedResult"));
    }
  }

  public record RejectInput(UUID questVersionId, long expectedRowVersion) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RejectInput parse(JsonNode node) {
      object(node, 2);
      return new RejectInput(uuid(node, "questVersionId"), version(node));
    }
  }

  public record VersionInput(long expectedRowVersion) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static VersionInput parse(JsonNode node) {
      object(node, 1);
      return new VersionInput(version(node));
    }
  }

  public record ResultInput(long expectedRowVersion, Result selectedResult) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ResultInput parse(JsonNode node) {
      object(node, 2);
      return new ResultInput(version(node), result(node, "selectedResult"));
    }
  }

  private static void object(JsonNode node, int size) {
    if (node == null || !node.isObject() || node.size() != size) throw invalid();
  }

  private static String text(JsonNode node, String name) {
    if (!node.path(name).isString()) throw invalid();
    return node.path(name).asString();
  }

  private static long version(JsonNode node) {
    JsonNode value = node.path("expectedRowVersion");
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
      throw invalid();
    return value.asLong();
  }

  private static UUID uuid(JsonNode node, String name) {
    try {
      String value = text(node, name);
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
  }

  private static Instant time(JsonNode node, String name) {
    try {
      return OffsetDateTime.parse(text(node, name)).toInstant();
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private static Result result(JsonNode node, String name) {
    try {
      return Result.valueOf(text(node, name));
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
  }

  public static ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "INVALID_TUTORIAL_REQUEST", "튜토리얼 요청과 날짜를 확인해 주세요.");
  }
}
