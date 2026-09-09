package com.betchu.backend.game;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.starter.StarterModels.Species;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

public final class GameModels {
  private GameModels() {}

  public enum GrowthStage {
    EGG,
    BABY,
    INTERMEDIATE,
    FINAL
  }

  public enum Milestone {
    HATCH,
    SUCCESS_20,
    SUCCESS_40,
    SUCCESS_60,
    SUCCESS_80
  }

  public record MonsterView(
      UUID id,
      String name,
      Species species,
      GrowthStage growthStage,
      int recognizedSuccessCount,
      Milestone nextGrowthMilestone,
      List<Milestone> reachedMilestones,
      List<String> masteryRewards,
      int accountLevel,
      int currentLevelExp,
      Integer nextLevelRequiredExp,
      int baseHp,
      int baseAttack,
      int equipmentBonusHp,
      int equipmentBonusAttack,
      int finalHp,
      int finalAttack,
      int combatPower,
      int streak) {}

  public record MonsterResult(MonsterView monster) {}

  public record HomeSelf(
      UUID id, String nickname, long availableCoins, long lockedCoins, MonsterView monster) {}

  public record HomePartner(UUID id, String nickname, String profileImage, MonsterView monster) {}

  public record QuestStatusSummary(long pendingApproval, long active, long awaitingResult) {}

  public record Home(
      Instant serverTime,
      UUID coupleId,
      HomeSelf self,
      HomePartner partner,
      long ownDraftCount,
      QuestStatusSummary questStatusSummary) {}

  public record RenameRequest(String name) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RenameRequest parse(JsonNode input) {
      if (input == null || !input.isObject() || input.size() != 1 || !input.path("name").isString())
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해 주세요.");
      return new RenameRequest(input.path("name").asString());
    }
  }
}
