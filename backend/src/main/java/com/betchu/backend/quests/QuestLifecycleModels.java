package com.betchu.backend.quests;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.tutorial.TutorialModels.CancelRequest;
import com.betchu.backend.tutorial.TutorialModels.PartnerSelection;
import com.betchu.backend.tutorial.TutorialModels.Result;
import com.betchu.backend.tutorial.TutorialModels.Settlement;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

public final class QuestLifecycleModels {
  private QuestLifecycleModels() {}

  public record Terms(
      String title,
      QuestModels.Category category,
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

  public record Version(
      UUID id, int versionNo, Terms terms, List<String> changedFields, Instant submittedAt) {}

  public record Decision(String decision, String requestMessage, Instant decidedAt) {}

  public record View(
      UUID id,
      UUID coupleId,
      UUID creatorId,
      String viewerRole,
      String status,
      String auditProjectionStatus,
      long rowVersion,
      UUID questVersionId,
      UUID approvedQuestVersionId,
      Version version,
      @JsonInclude(JsonInclude.Include.NON_NULL) QuestModels.DraftView draft,
      Result predictedResult,
      LocalDate budgetDateKst,
      Integer lockedStake,
      Integer rewardReservedAmount,
      Decision decision,
      @JsonInclude(JsonInclude.Include.NON_NULL) PartnerSelection partnerSelection,
      CancelRequest cancelRequest,
      Settlement settlement,
      List<String> allowedActions,
      Instant serverTime) {}

  public record Page(List<View> quests, String nextCursor, Instant serverTime) {}

  public record Versions(List<Version> versions, long rowVersion) {}

  public record ActivitySummary(
      Instant weekStartAt, Instant weekEndAt, long current, int target, boolean unlocked) {}

  public record Quote(
      UUID questId,
      long rowVersion,
      LocalDate budgetDateKst,
      long availableCoins,
      int activeSlotsRemaining,
      int xpSlotsRemaining,
      int coinSlotsRemaining,
      int totalBonusRemaining,
      int lowBonusRemaining,
      int expectedReward,
      List<Integer> allowedStakes,
      boolean canApprove,
      String blockingReason) {}

  public record Risk(
      UUID coupleId,
      boolean suspended,
      int generation,
      Instant suspendedAt,
      Instant acknowledgedAt,
      boolean partnerAcknowledged,
      Instant serverTime) {}

  public record RowInput(long expectedRowVersion) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RowInput parse(JsonNode input) {
      object(input, 1);
      return new RowInput(row(input));
    }
  }

  public record VersionInput(UUID questVersionId, long expectedRowVersion) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static VersionInput parse(JsonNode input) {
      object(input, 2);
      return new VersionInput(uuid(input, "questVersionId"), row(input));
    }
  }

  public record ApproveInput(UUID questVersionId, long expectedRowVersion, Result predictedResult) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ApproveInput parse(JsonNode input) {
      object(input, 3);
      return new ApproveInput(
          uuid(input, "questVersionId"), row(input), result(input, "predictedResult"));
    }
  }

  public record ChangeInput(UUID questVersionId, long expectedRowVersion, String requestMessage) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ChangeInput parse(JsonNode input) {
      object(input, 3);
      return new ChangeInput(
          uuid(input, "questVersionId"), row(input), text(input, "requestMessage"));
    }
  }

  public record ResultInput(long expectedRowVersion, Result selectedResult) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ResultInput parse(JsonNode input) {
      object(input, 2);
      return new ResultInput(row(input), result(input, "selectedResult"));
    }
  }

  public record FinalInput(long expectedRowVersion, Result selectedResult, int selectionRevision) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FinalInput parse(JsonNode input) {
      object(input, 3);
      int revision = integer(input, "selectionRevision");
      if (revision < 1) throw invalid();
      return new FinalInput(row(input), result(input, "selectedResult"), revision);
    }
  }

  public record AcknowledgeInput(int expectedGeneration) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AcknowledgeInput parse(JsonNode input) {
      object(input, 1);
      int generation = integer(input, "expectedGeneration");
      if (generation < 0) throw invalid();
      return new AcknowledgeInput(generation);
    }
  }

  private static void object(JsonNode input, int size) {
    if (input == null || !input.isObject() || input.size() != size) throw invalid();
  }

  private static long row(JsonNode input) {
    JsonNode value = input.path("expectedRowVersion");
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
      throw invalid();
    return value.asLong();
  }

  private static int integer(JsonNode input, String field) {
    JsonNode value = input.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
    return value.asInt();
  }

  private static String text(JsonNode input, String field) {
    if (!input.path(field).isString()) throw invalid();
    return input.path(field).asString();
  }

  private static UUID uuid(JsonNode input, String field) {
    try {
      String text = text(input, field);
      UUID id = UUID.fromString(text);
      if (!id.toString().equalsIgnoreCase(text)) throw invalid();
      return id;
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static Result result(JsonNode input, String field) {
    try {
      return Result.valueOf(text(input, field));
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  public static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUEST_REQUEST", "퀘스트 요청 형식을 확인해 주세요.");
  }

  public static ApiException conflict(String code, String detail) {
    return new ApiException(HttpStatus.CONFLICT, code, detail);
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "QUEST_NOT_FOUND", "현재 연결에서 퀘스트를 확인할 수 없어요.");
  }
}
